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

package org.springframework.cloud.gateway.discovery;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.style.ToStringCreator;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.util.StringUtils;

/**
 * TODO: change to RouteLocator? use java dsl
 *
 * 服务发现路由定位器
 * @author Spencer Gibb
 */
public class DiscoveryClientRouteDefinitionLocator implements RouteDefinitionLocator {

	private static final Log log = LogFactory.getLog(DiscoveryClientRouteDefinitionLocator.class);
	/**
	 * 网关的服务发现定位器属性
	 */
	private final DiscoveryLocatorProperties properties;
	/**
	 * 路由id的前缀
	 */
	private final String routeIdPrefix;
	/**
	 * 评估上下文
	 */
	private final SimpleEvaluationContext evalCtxt;
	/**
	 * 服务实例集合
	 */
	private Flux<List<ServiceInstance>> serviceInstances;

	public DiscoveryClientRouteDefinitionLocator(ReactiveDiscoveryClient discoveryClient,
			DiscoveryLocatorProperties properties) {
		this(discoveryClient.getClass().getSimpleName(), properties);
		serviceInstances = discoveryClient.getServices()
				.flatMap(service -> discoveryClient.getInstances(service).collectList());
	}

	private DiscoveryClientRouteDefinitionLocator(String discoveryClientName, DiscoveryLocatorProperties properties) {
		this.properties = properties;
		if (StringUtils.hasText(properties.getRouteIdPrefix())) {
			routeIdPrefix = properties.getRouteIdPrefix();
		}
		else {
			routeIdPrefix = discoveryClientName + "_";
		}
		evalCtxt = SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods().build();
	}

	@Override
	public Flux<RouteDefinition> getRouteDefinitions() {

		// 创建spring-el表达式解析器
		SpelExpressionParser parser = new SpelExpressionParser();
		// 解析includeExpression数据
		Expression includeExpr = parser.parseExpression(properties.getIncludeExpression());
		// 解析urlExpression数据
		Expression urlExpr = parser.parseExpression(properties.getUrlExpression());

		Predicate<ServiceInstance> includePredicate;
		if (properties.getIncludeExpression() == null || "true".equalsIgnoreCase(properties.getIncludeExpression())) {
			includePredicate = instance -> true;
		}
		else {
			includePredicate = instance -> {
				Boolean include = includeExpr.getValue(evalCtxt, instance, Boolean.class);
				if (include == null) {
					return false;
				}
				return include;
			};
		}

		return serviceInstances.filter(instances -> !instances.isEmpty())
				.map(instances -> instances.get(0))
				.filter(includePredicate).map(instance -> {
					// 创建路由定义对象
					RouteDefinition routeDefinition = buildRouteDefinition(urlExpr, instance);

					// 创建服务实例
					final ServiceInstance instanceForEval = new DelegatingServiceInstance(instance,
							properties);

					// 获取谓词定义集合
					for (PredicateDefinition original : this.properties.getPredicates()) {
						// 创建谓词定义
						PredicateDefinition predicate = new PredicateDefinition();
						// 设置名称
						predicate.setName(original.getName());
						// 将配置表中的谓词定义转换到谓词定义中
						for (Map.Entry<String, String> entry : original.getArgs().entrySet()) {
							String value = getValueFromExpr(evalCtxt, parser, instanceForEval,
									entry);
							predicate.addArg(entry.getKey(), value);
						}
						// 将转换后的谓词定义放入到路由定义中
						routeDefinition.getPredicates().add(predicate);
					}

					// 获取过滤器定义集合
					for (FilterDefinition original : this.properties.getFilters()) {
						// 创建过滤器定义
						FilterDefinition filter = new FilterDefinition();
						// 设置过滤器定义名称
						filter.setName(original.getName());
						// 将配置表中的过滤器定义转换到过滤器定义中
						for (Map.Entry<String, String> entry : original.getArgs().entrySet()) {
							String value = getValueFromExpr(evalCtxt, parser, instanceForEval,
									entry);
							filter.addArg(entry.getKey(), value);
						}
						// 将转换后的过滤器定义放入到路由定义中
						routeDefinition.getFilters().add(filter);
					}

					return routeDefinition;
				});
	}

	protected RouteDefinition buildRouteDefinition(Expression urlExpr,
			ServiceInstance serviceInstance) {
		// 获取服务id
		String serviceId = serviceInstance.getServiceId();
		// 创建路由定义对象
		RouteDefinition routeDefinition = new RouteDefinition();
		// 设置id，设置规则是前缀+服务id
		routeDefinition.setId(this.routeIdPrefix + serviceId);
		// 解析url表达式
		String uri = urlExpr.getValue(this.evalCtxt, serviceInstance, String.class);
		// 设置路由地址
		routeDefinition.setUri(URI.create(uri));
		// add instance metadata
		// 补充路由定义对象中的元信息
		routeDefinition.setMetadata(new LinkedHashMap<>(serviceInstance.getMetadata()));
		return routeDefinition;
	}

	String getValueFromExpr(SimpleEvaluationContext evalCtxt, SpelExpressionParser parser, ServiceInstance instance,
			Map.Entry<String, String> entry) {
		try {
			Expression valueExpr = parser.parseExpression(entry.getValue());
			return valueExpr.getValue(evalCtxt, instance, String.class);
		}
		catch (ParseException | EvaluationException e) {
			if (log.isDebugEnabled()) {
				log.debug("Unable to parse " + entry.getValue(), e);
			}
			throw e;
		}
	}

	private static class DelegatingServiceInstance implements ServiceInstance {

		/**
		 * 服务实例
		 */
		final ServiceInstance delegate;

		/**
		 * 网关的服务发现定位器属性
		 */
		private final DiscoveryLocatorProperties properties;

		private DelegatingServiceInstance(ServiceInstance delegate, DiscoveryLocatorProperties properties) {
			this.delegate = delegate;
			this.properties = properties;
		}

		@Override
		public String getServiceId() {
			if (properties.isLowerCaseServiceId()) {
				return delegate.getServiceId().toLowerCase();
			}
			return delegate.getServiceId();
		}

		@Override
		public String getHost() {
			return delegate.getHost();
		}

		@Override
		public int getPort() {
			return delegate.getPort();
		}

		@Override
		public boolean isSecure() {
			return delegate.isSecure();
		}

		@Override
		public URI getUri() {
			return delegate.getUri();
		}

		@Override
		public Map<String, String> getMetadata() {
			return delegate.getMetadata();
		}

		@Override
		public String getScheme() {
			return delegate.getScheme();
		}

		@Override
		public String toString() {
			return new ToStringCreator(this).append("delegate", delegate).append("properties", properties).toString();
		}

	}

}
