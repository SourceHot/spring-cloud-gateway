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

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.support.tagsprovider.GatewayTagsProvider;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;

/**
 * 网关指标过滤器
 * @author Tony Clarke
 * @author Ingyu Hwang
 */
public class GatewayMetricsFilter implements GlobalFilter, Ordered {

	private static final Log log = LogFactory.getLog(GatewayMetricsFilter.class);

	/**
	 * 指标注册器
	 */
	private final MeterRegistry meterRegistry;

	/**
	 * 网关标签提供器
	 */
	private GatewayTagsProvider compositeTagsProvider;

	/**
	 * 指标前缀
	 */
	private final String metricsPrefix;

	public GatewayMetricsFilter(MeterRegistry meterRegistry, List<GatewayTagsProvider> tagsProviders,
			String metricsPrefix) {
		this.meterRegistry = meterRegistry;
		this.compositeTagsProvider = tagsProviders.stream().reduce(exchange -> Tags.empty(), GatewayTagsProvider::and);
		if (metricsPrefix.endsWith(".")) {
			this.metricsPrefix = metricsPrefix.substring(0, metricsPrefix.length() - 1);
		}
		else {
			this.metricsPrefix = metricsPrefix;
		}
	}

	public String getMetricsPrefix() {
		return metricsPrefix;
	}

	@Override
	public int getOrder() {
		// start the timer as soon as possible and report the metric event before we write
		// response to client
		return NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER + 1;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// 生成开始时间
		Sample sample = Timer.start(meterRegistry);

		return chain.filter(exchange)
				.doOnSuccess(aVoid -> endTimerRespectingCommit(exchange, sample))
				.doOnError(throwable -> endTimerRespectingCommit(exchange, sample));
	}

	private void endTimerRespectingCommit(ServerWebExchange exchange, Sample sample) {

		// 获取响应对象
		ServerHttpResponse response = exchange.getResponse();
		// 判断响应是否提交结束
		if (response.isCommitted()) {
			endTimerInner(exchange, sample);
		} else {
			// 提交之前推送时间对象
			response.beforeCommit(() -> {
				endTimerInner(exchange, sample);
				return Mono.empty();
			});
		}
	}

	private void endTimerInner(ServerWebExchange exchange, Sample sample) {
		// 计算标签集合
		Tags tags = compositeTagsProvider.apply(exchange);

		if (log.isTraceEnabled()) {
			log.trace(metricsPrefix + ".requests tags: " + tags);
		}
		// 停止时间计算器计算时间差将指标进行注册
		sample.stop(meterRegistry.timer(metricsPrefix + ".requests", tags));
	}

}
