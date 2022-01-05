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

import java.net.URI;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.CompletionContext;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycleValidator;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.client.loadbalancer.ResponseData;
import org.springframework.cloud.gateway.config.GatewayLoadBalancerProperties;
import org.springframework.cloud.gateway.support.DelegatingServiceInstance;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_SCHEME_PREFIX_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.addOriginalRequestUrl;

/**
 * A {@link GlobalFilter} implementation that routes requests using reactive Spring Cloud
 * LoadBalancer.
 * <p>
 * 响应式负载均衡过滤器
 *
 * @author Spencer Gibb
 * @author Tim Ysewyn
 * @author Olga Maciaszek-Sharma
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class ReactiveLoadBalancerClientFilter implements GlobalFilter, Ordered {

	private static final Log log = LogFactory.getLog(ReactiveLoadBalancerClientFilter.class);

	/**
	 * Order of filter.
	 */
	public static final int LOAD_BALANCER_CLIENT_FILTER_ORDER = 10150;

	private final LoadBalancerClientFactory clientFactory;

	private final GatewayLoadBalancerProperties properties;

	private final LoadBalancerProperties loadBalancerProperties;

	public ReactiveLoadBalancerClientFilter(LoadBalancerClientFactory clientFactory,
			GatewayLoadBalancerProperties properties, LoadBalancerProperties loadBalancerProperties) {
		this.clientFactory = clientFactory;
		this.properties = properties;
		this.loadBalancerProperties = loadBalancerProperties;
	}

	@Override
	public int getOrder() {
		return LOAD_BALANCER_CLIENT_FILTER_ORDER;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// 获取URI对象
		URI url = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
		// 获取方案前缀
		String schemePrefix = exchange.getAttribute(GATEWAY_SCHEME_PREFIX_ATTR);
		// 1. URI对象为空
		// 2. 方案不是lb并且前缀不是lb
		if (url == null || (!"lb".equals(url.getScheme()) && !"lb".equals(schemePrefix))) {
			return chain.filter(exchange);
		}
		// preserve the original url
		// 为exchange设置远端url属性
		addOriginalRequestUrl(exchange, url);

		if (log.isTraceEnabled()) {
			log.trace(
					ReactiveLoadBalancerClientFilter.class.getSimpleName() + " url before: " + url);
		}

		// 获取URI对象
		URI requestUri = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
		// 获取服务id
		String serviceId = requestUri.getHost();
		// 获取负载均衡相关的生命周期实例
		Set<LoadBalancerLifecycle> supportedLifecycleProcessors = LoadBalancerLifecycleValidator
				.getSupportedLifecycleProcessors(
						clientFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
						RequestDataContext.class, ResponseData.class, ServiceInstance.class);
		// 创建默认的请求对象
		DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(new RequestDataContext(
				new RequestData(exchange.getRequest()),
				getHint(serviceId, loadBalancerProperties.getHint())));
		// 选择服务实例进行请求处理
		return choose(lbRequest, serviceId, supportedLifecycleProcessors).doOnNext(response -> {

					// 如果响应不包含服务则抛出异常
					if (!response.hasServer()) {
						supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
								.onComplete(
										new CompletionContext<>(CompletionContext.Status.DISCARD, lbRequest,
												response)));
						throw NotFoundException.create(properties.isUse404(),
								"Unable to find instance for " + url.getHost());
					}
					// 从响应中获取服务实例
					ServiceInstance retrievedInstance = response.getServer();
					// 获取URI对象
					URI uri = exchange.getRequest().getURI();

					// if the `lb:<scheme>` mechanism was used, use `<scheme>` as the default,
					// if the loadbalancer doesn't provide one.
					// 确认是否是安全的，如果是则将方案设置为https反之则设置为http
					String overrideScheme = retrievedInstance.isSecure() ? "https" : "http";
					// 方案前缀不为空的情况下再次覆盖方案
					if (schemePrefix != null) {
						overrideScheme = url.getScheme();
					}

					// 构造委托服务实例对象
					DelegatingServiceInstance serviceInstance = new DelegatingServiceInstance(
							retrievedInstance,
							overrideScheme);
					// 创建需要发送的请求地址
					URI requestUrl = reconstructURI(serviceInstance, uri);

					if (log.isTraceEnabled()) {
						log.trace("LoadBalancerClientFilter url chosen: " + requestUrl);
					}
					// 设置GATEWAY_REQUEST_URL_ATTR属性和GATEWAY_LOADBALANCER_RESPONSE_ATTR属性
					exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, requestUrl);
					exchange.getAttributes().put(GATEWAY_LOADBALANCER_RESPONSE_ATTR, response);
					// 生命周期onStartRequest的执行
					supportedLifecycleProcessors.forEach(
							lifecycle -> lifecycle.onStartRequest(lbRequest, response));
				})
				// 责任链处理
				.then(chain.filter(exchange))
				// 出现异常的情况,执行onComplete生命周期
				.doOnError(throwable -> supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
						.onComplete(
								new CompletionContext<ResponseData, ServiceInstance, RequestDataContext>(
										CompletionContext.Status.FAILED, throwable, lbRequest,
										exchange.getAttribute(
												GATEWAY_LOADBALANCER_RESPONSE_ATTR)))))
				// 未出现异常的情况,执行onComplete生命周期
				.doOnSuccess(aVoid -> supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
						.onComplete(
								new CompletionContext<ResponseData, ServiceInstance, RequestDataContext>(
										CompletionContext.Status.SUCCESS, lbRequest,
										exchange.getAttribute(GATEWAY_LOADBALANCER_RESPONSE_ATTR),
										new ResponseData(exchange.getResponse(),
												new RequestData(exchange.getRequest()))))));
	}

	protected URI reconstructURI(ServiceInstance serviceInstance, URI original) {
		return LoadBalancerUriTools.reconstructURI(serviceInstance, original);
	}

	private Mono<Response<ServiceInstance>> choose(Request<RequestDataContext> lbRequest,
			String serviceId,
			Set<LoadBalancerLifecycle> supportedLifecycleProcessors) {
		// 通过客户端工厂根据服务id获取负载均衡实例
		ReactorLoadBalancer<ServiceInstance> loadBalancer = this.clientFactory.getInstance(
				serviceId,
				ReactorServiceInstanceLoadBalancer.class);
		// 如果负载均衡实例为空抛出异常
		if (loadBalancer == null) {
			throw new NotFoundException("No loadbalancer available for " + serviceId);
		}
		// 执行生命周期的onStart方法
		supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));
		// 从负载均衡实例中选择结果
		return loadBalancer.choose(lbRequest);
	}

	private String getHint(String serviceId, Map<String, String> hints) {
		String defaultHint = hints.getOrDefault("default", "default");
		String hintPropertyValue = hints.get(serviceId);
		return hintPropertyValue != null ? hintPropertyValue : defaultHint;
	}

}
