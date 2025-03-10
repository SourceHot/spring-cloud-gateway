/*
 * Copyright 2013-2019 the original author or authors.
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

package org.springframework.cloud.gateway.sample;

import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.isomorphism.util.TokenBucket;
import org.isomorphism.util.TokenBuckets;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;

/**
 * Sample throttling filter. See https://github.com/bbeck/token-bucket
 *
 * 限流过滤器
 */
public class ThrottleGatewayFilter implements GatewayFilter {

	private static final Log log = LogFactory.getLog(ThrottleGatewayFilter.class);

	/**
	 * 容量
	 */
	int capacity;

	/**
	 * 填充数量
	 */
	int refillTokens;

	/**
	 * 间隔时间
	 */
	int refillPeriod;

	/**
	 * 间隔时间的单位
	 */
	TimeUnit refillUnit;

	public int getCapacity() {
		return capacity;
	}

	public ThrottleGatewayFilter setCapacity(int capacity) {
		this.capacity = capacity;
		return this;
	}

	public int getRefillTokens() {
		return refillTokens;
	}

	public ThrottleGatewayFilter setRefillTokens(int refillTokens) {
		this.refillTokens = refillTokens;
		return this;
	}

	public int getRefillPeriod() {
		return refillPeriod;
	}

	public ThrottleGatewayFilter setRefillPeriod(int refillPeriod) {
		this.refillPeriod = refillPeriod;
		return this;
	}

	public TimeUnit getRefillUnit() {
		return refillUnit;
	}

	public ThrottleGatewayFilter setRefillUnit(TimeUnit refillUnit) {
		this.refillUnit = refillUnit;
		return this;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

		// 构建TokenBucket
		TokenBucket tokenBucket = TokenBuckets.builder().withCapacity(capacity)
				.withFixedIntervalRefillStrategy(refillTokens, refillPeriod, refillUnit).build();

		// TODO: get a token bucket for a key
		log.debug("TokenBucket capacity: " + tokenBucket.getCapacity());
		// 尝试是否可以消费，如果可以则进行责任链处理
		boolean consumed = tokenBucket.tryConsume();
		if (consumed) {
			return chain.filter(exchange);
		}
		// 设置响应状态码为429，表示请求过多
		exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
		// 获取响应对象设置处理完成标记
		return exchange.getResponse().setComplete();
	}

}
