/*
 * Copyright 2013-2021 the original author or authors.
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
import java.util.List;

import reactor.core.publisher.Mono;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR;

/**
 * A {@link GlobalFilter} that allows passing the {@code} instanceId) of the
 * {@link ServiceInstance} selected by the {@link ReactiveLoadBalancerClientFilter} in a
 * cookie.
 *
 * 负载均衡过滤器，带cookie
 * @author Olga Maciaszek-Sharma
 * @since 3.0.2
 */
public class LoadBalancerServiceInstanceCookieFilter implements GlobalFilter, Ordered {

	private final LoadBalancerProperties loadBalancerProperties;

	public LoadBalancerServiceInstanceCookieFilter(LoadBalancerProperties loadBalancerProperties) {
		this.loadBalancerProperties = loadBalancerProperties;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// 获取服务实例
		Response<ServiceInstance> serviceInstanceResponse = exchange.getAttribute(
				GATEWAY_LOADBALANCER_RESPONSE_ATTR);
		// 如果服务实例对象为空或者服务实例对象中没有服务对象交给责任链处理
		if (serviceInstanceResponse == null || !serviceInstanceResponse.hasServer()) {
			return chain.filter(exchange);
		}
		// 通过负载均衡配置获取实例id的cookie名称
		String instanceIdCookieName = loadBalancerProperties.getStickySession()
				.getInstanceIdCookieName();
		// 实例id的cookie名称为空交给责任链处理
		if (!StringUtils.hasText(instanceIdCookieName)) {
			return chain.filter(exchange);
		}
		// 构造新的exchange对象再交给责任链处理
		ServerWebExchange newExchange = exchange.mutate()
				.request(exchange.getRequest().mutate().headers((headers) -> {

					List<String> cookieHeaders = new ArrayList<>(
							headers.getOrEmpty(HttpHeaders.COOKIE));
					String serviceInstanceCookie = new HttpCookie(instanceIdCookieName,
							serviceInstanceResponse.getServer().getInstanceId()).toString();
					cookieHeaders.add(serviceInstanceCookie);
					headers.put(HttpHeaders.COOKIE, cookieHeaders);
				}).build()).build();
		return chain.filter(newExchange);
	}

	@Override
	public int getOrder() {
		return LOAD_BALANCER_CLIENT_FILTER_ORDER + 1;
	}

}
