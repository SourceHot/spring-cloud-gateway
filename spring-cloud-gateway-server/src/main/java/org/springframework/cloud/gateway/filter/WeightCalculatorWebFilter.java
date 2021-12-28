/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.filter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.event.PredicateArgsEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.event.WeightDefinedEvent;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.cloud.gateway.support.WeightConfig;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.style.ToStringCreator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.WEIGHT_ATTR;

/**
 * 权重计算过滤器
 * @author Spencer Gibb
 * @author Alexey Nakidkin
 */
public class WeightCalculatorWebFilter implements WebFilter, Ordered, SmartApplicationListener {

	/**
	 * Order of Weight Calculator Web filter.
	 */
	public static final int WEIGHT_CALC_FILTER_ORDER = 10001;

	private static final Log log = LogFactory.getLog(WeightCalculatorWebFilter.class);

	/**
	 * 路由定位器
	 */
	private final ObjectProvider<RouteLocator> routeLocator;

	/**
	 * 配置服务
	 */
	private final ConfigurationService configurationService;

	/**
	 * 随机对象
	 */
	private Random random = new Random();

	/**
	 * 序号
	 */
	private int order = WEIGHT_CALC_FILTER_ORDER;

	/**
	 * 组别权重映射表
	 */
	private Map<String, GroupWeightConfig> groupWeights = new ConcurrentHashMap<>();


	/**
	 * 路由定位器是否初始化
	 */
	private final AtomicBoolean routeLocatorInitialized = new AtomicBoolean();

	public WeightCalculatorWebFilter(ObjectProvider<RouteLocator> routeLocator,
			ConfigurationService configurationService) {
		this.routeLocator = routeLocator;
		this.configurationService = configurationService;
	}

	/* for testing */
	static Map<String, String> getWeights(ServerWebExchange exchange) {
		Map<String, String> weights = exchange.getAttribute(WEIGHT_ATTR);

		if (weights == null) {
			weights = new ConcurrentHashMap<>();
			exchange.getAttributes().put(WEIGHT_ATTR, weights);
		}
		return weights;
	}

	@Override
	public int getOrder() {
		return order;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	public void setRandom(Random random) {
		this.random = random;
	}

	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
		// from config file
		return PredicateArgsEvent.class.isAssignableFrom(eventType) ||
		// from java dsl
				WeightDefinedEvent.class.isAssignableFrom(eventType) ||
				// force initialization
				RefreshRoutesEvent.class.isAssignableFrom(eventType);
	}

	@Override
	public boolean supportsSourceType(Class<?> sourceType) {
		return true;
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if (event instanceof PredicateArgsEvent) {
			handle((PredicateArgsEvent) event);
		}
		else if (event instanceof WeightDefinedEvent) {
			addWeightConfig(((WeightDefinedEvent) event).getWeightConfig());
		}
		else if (event instanceof RefreshRoutesEvent && routeLocator != null) {
			// forces initialization
			if (routeLocatorInitialized.compareAndSet(false, true)) {
				// on first time, block so that app fails to start if there are errors in
				// routes
				// see gh-1574
				routeLocator.ifAvailable(locator -> locator.getRoutes().blockLast());
			}
			else {
				// this preserves previous behaviour on refresh, this could likely go away
				routeLocator.ifAvailable(locator -> locator.getRoutes().subscribe());
			}
		}

	}

	public void handle(PredicateArgsEvent event) {
		// 从事件对象中获取参数对象
		Map<String, Object> args = event.getArgs();
		// 如果参数对象为空或者不包含weight+"."的键则跳过处理
		if (args.isEmpty() || !hasRelevantKey(args)) {
			return;
		}
		// 创建权重配置
		WeightConfig config = new WeightConfig(event.getRouteId());
		// 根据权重配置在配置服务中搜索实例设置名称和配置并绑定
		this.configurationService.with(config).name(WeightConfig.CONFIG_PREFIX).normalizedProperties(args).bind();
		// 添加权重信息
		addWeightConfig(config);
	}

	private boolean hasRelevantKey(Map<String, Object> args) {
		return args.keySet().stream().anyMatch(key -> key.startsWith(WeightConfig.CONFIG_PREFIX + "."));
	}

	void addWeightConfig(WeightConfig weightConfig) {
		// 提取组别名称
		String group = weightConfig.getGroup();

		GroupWeightConfig config;
		// only create new GroupWeightConfig rather than modify
		// and put at end of calculations. This avoids concurency problems
		// later during filter execution.
		// 组别权重映射表中是否包含当前组别，如果包含则直接获取并转换为GroupWeightConfig，反之则创建GroupWeightConfig
		if (groupWeights.containsKey(group)) {
			config = new GroupWeightConfig(groupWeights.get(group));
		} else {
			config = new GroupWeightConfig(group);
		}

		// 向组别权重配置中加入当前路由id和权重信息
		config.weights.put(weightConfig.getRouteId(), weightConfig.getWeight());

		// recalculate

		// normalize weights
		// 权重和
		int weightsSum = 0;

		// 计算权重和
		for (Integer weight : config.weights.values()) {
			weightsSum += weight;
		}

		final AtomicInteger index = new AtomicInteger(0);
		// 对历史权重进行处理
		for (Map.Entry<String, Integer> entry : config.weights.entrySet()) {
			// 获取路由id
			String routeId = entry.getKey();
			// 获取权重
			Integer weight = entry.getValue();
			// 当前权重除以权重和得到占比
			Double nomalizedWeight = weight / (double) weightsSum;
			// 设置到归一后的权重表
			config.normalizedWeights.put(routeId, nomalizedWeight);

			// recalculate rangeIndexes
			// 设置索引信息
			config.rangeIndexes.put(index.getAndIncrement(), routeId);
		}

		// TODO: calculate ranges
		// 重新计算ranges数据
		config.ranges.clear();

		config.ranges.add(0.0);

		List<Double> values = new ArrayList<>(config.normalizedWeights.values());
		for (int i = 0; i < values.size(); i++) {
			Double currentWeight = values.get(i);
			Double previousRange = config.ranges.get(i);
			Double range = previousRange + currentWeight;
			config.ranges.add(range);
		}

		if (log.isTraceEnabled()) {
			log.trace("Recalculated group weight config " + config);
		}
		// only update after all calculations
		// 加入到组别权重信息中
		groupWeights.put(group, config);
	}

	/* for testing */ Map<String, GroupWeightConfig> getGroupWeights() {
		return groupWeights;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		// 请求进入的第一个入口
		Map<String, String> weights = getWeights(exchange);

		for (String group : groupWeights.keySet()) {
			GroupWeightConfig config = groupWeights.get(group);

			if (config == null) {
				if (log.isDebugEnabled()) {
					log.debug("No GroupWeightConfig found for group: " + group);
				}
				continue; // nothing we can do, but this is odd
			}

			double r = this.random.nextDouble();

			List<Double> ranges = config.ranges;

			if (log.isTraceEnabled()) {
				log.trace("Weight for group: " + group + ", ranges: " + ranges + ", r: " + r);
			}

			for (int i = 0; i < ranges.size() - 1; i++) {
				if (r >= ranges.get(i) && r < ranges.get(i + 1)) {
					String routeId = config.rangeIndexes.get(i);
					weights.put(group, routeId);
					break;
				}
			}
		}

		if (log.isTraceEnabled()) {
			log.trace("Weights attr: " + weights);
		}

		return chain.filter(exchange);
	}

	/* for testing */ static class GroupWeightConfig {

		String group;

		LinkedHashMap<String, Integer> weights = new LinkedHashMap<>();

		/**
		 * 归并后的路由权重。
		 * key:路由id
		 * value:权重信息
		 */
		LinkedHashMap<String, Double> normalizedWeights = new LinkedHashMap<>();

		LinkedHashMap<Integer, String> rangeIndexes = new LinkedHashMap<>();

		List<Double> ranges = new ArrayList<>();

		GroupWeightConfig(String group) {
			this.group = group;
		}

		GroupWeightConfig(GroupWeightConfig other) {
			this.group = other.group;
			this.weights = new LinkedHashMap<>(other.weights);
			this.normalizedWeights = new LinkedHashMap<>(other.normalizedWeights);
			this.rangeIndexes = new LinkedHashMap<>(other.rangeIndexes);
		}

		@Override
		public String toString() {
			return new ToStringCreator(this).append("group", group).append("weights", weights)
					.append("normalizedWeights", normalizedWeights).append("rangeIndexes", rangeIndexes).toString();
		}

	}

}
