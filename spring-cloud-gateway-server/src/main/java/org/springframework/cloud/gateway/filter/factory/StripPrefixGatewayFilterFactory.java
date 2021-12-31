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

package org.springframework.cloud.gateway.filter.factory;

import java.util.Arrays;
import java.util.List;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.GatewayToStringStyler.filterToStringCreator;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.addOriginalRequestUrl;

/**
 * This filter removes the first part of the path, known as the prefix, from the request
 * before sending it downstream.
 *
 * 前缀剥离器
 * @author Ryan Baxter
 */
public class StripPrefixGatewayFilterFactory
		extends AbstractGatewayFilterFactory<StripPrefixGatewayFilterFactory.Config> {

	/**
	 * Parts key.
	 */
	public static final String PARTS_KEY = "parts";

	public StripPrefixGatewayFilterFactory() {
		super(Config.class);
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(PARTS_KEY);
	}

	@Override
	public GatewayFilter apply(Config config) {
		return new GatewayFilter() {
			@Override
			public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
				// 获取原始请求对象
				ServerHttpRequest request = exchange.getRequest();
				// 添加原始路由地址
				addOriginalRequestUrl(exchange, request.getURI());
				// 获取请求地址
				String path = request.getURI().getRawPath();
				// 根据/进行字符串拆分
				String[] originalParts = StringUtils.tokenizeToStringArray(path, "/");

				// all new paths start with /
				// 创建"/"字符串对象,保证新的路由地址是以/开头
				StringBuilder newPath = new StringBuilder("/");
				// 循环字符串拆分结果将数据加入到newPath变量中
				for (int i = 0; i < originalParts.length; i++) {
					// 超过配置文件中的parts数据则会加入到newPath
					if (i >= config.getParts()) {
						// only append slash if this is the second part or greater
						if (newPath.length() > 1) {
							newPath.append('/');
						}
						newPath.append(originalParts[i]);
					}
				}
				if (newPath.length() > 1 && path.endsWith("/")) {
					newPath.append('/');
				}

				// 构造新的请求对象
				ServerHttpRequest newRequest = request.mutate().path(newPath.toString()).build();

				// 设置GATEWAY_REQUEST_URL_ATTR属性,属性值为新的路由地址
				exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, newRequest.getURI());

				return chain.filter(exchange.mutate().request(newRequest).build());
			}

			@Override
			public String toString() {
				return filterToStringCreator(StripPrefixGatewayFilterFactory.this).append("parts", config.getParts())
						.toString();
			}
		};
	}

	public static class Config {

		private int parts;

		public int getParts() {
			return parts;
		}

		public void setParts(int parts) {
			this.parts = parts;
		}

	}

}
