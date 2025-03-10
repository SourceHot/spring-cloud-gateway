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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_ATTR;

/**
 * 与web客户端相关的响应写出过滤器
 * @author Spencer Gibb
 */
public class WebClientWriteResponseFilter implements GlobalFilter, Ordered {

	/**
	 * Order of Write Response Filter.
	 */
	public static final int WRITE_RESPONSE_FILTER_ORDER = -1;

	private static final Log log = LogFactory.getLog(WebClientWriteResponseFilter.class);

	@Override
	public int getOrder() {
		return WRITE_RESPONSE_FILTER_ORDER;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// NOTICE: nothing in "pre" filter stage as CLIENT_RESPONSE_ATTR is not added
		// until the WebHandler is run
		return chain.filter(exchange).doOnError(throwable -> cleanup(exchange))
				.then(Mono.defer(() -> {
					// 获取客户端响应对象
					ClientResponse clientResponse = exchange.getAttribute(CLIENT_RESPONSE_ATTR);
					// 如果客户端响应对象为空返回Mono的空对象
					if (clientResponse == null) {
						return Mono.empty();
					}
					log.trace("WebClientWriteResponseFilter start");
					// 获取服务响应对象
					ServerHttpResponse response = exchange.getResponse();
					// 通过客户端响应对象包装后写出,结束后关闭客户端响应对象
					return response.writeWith(clientResponse.body(BodyExtractors.toDataBuffers()))
							// .log("webClient response")
							.doOnCancel(() -> cleanup(exchange));
				}));
	}

	private void cleanup(ServerWebExchange exchange) {
		// 关闭客户端响应对象
		ClientResponse clientResponse = exchange.getAttribute(CLIENT_RESPONSE_ATTR);
		if (clientResponse != null) {
			clientResponse.bodyToMono(Void.class).subscribe();
		}
	}

}
