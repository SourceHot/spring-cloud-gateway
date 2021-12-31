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

package org.springframework.cloud.gateway.filter.factory.rewrite;

import java.util.List;
import java.util.function.Function;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.GatewayToStringStyler.filterToStringCreator;

/**
 * GatewayFilter that modifies the request body.
 *
 * 修改请求体的过滤器工厂
 */
public class ModifyRequestBodyGatewayFilterFactory
		extends AbstractGatewayFilterFactory<ModifyRequestBodyGatewayFilterFactory.Config> {

	private final List<HttpMessageReader<?>> messageReaders;

	public ModifyRequestBodyGatewayFilterFactory() {
		super(Config.class);
		this.messageReaders = HandlerStrategies.withDefaults().messageReaders();
	}

	public ModifyRequestBodyGatewayFilterFactory(List<HttpMessageReader<?>> messageReaders) {
		super(Config.class);
		this.messageReaders = messageReaders;
	}

	@Override
	@SuppressWarnings("unchecked")
	public GatewayFilter apply(Config config) {
		return new GatewayFilter() {
			@Override
			public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
				// 获取输入类型
				Class inClass = config.getInClass();
				// 创建请求对象
				ServerRequest serverRequest = ServerRequest.create(exchange, messageReaders);

				// TODO: flux or mono
				// 修改请求体信息
				Mono<?> modifiedBody = serverRequest.bodyToMono(inClass)
						.flatMap(originalBody -> config.getRewriteFunction()
								.apply(exchange, originalBody))
						.switchIfEmpty(Mono.defer(
								() -> (Mono) config.getRewriteFunction().apply(exchange, null)));

				// 创建请求体写入器
				BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody,
						config.getOutClass());
				// 设置头信息
				HttpHeaders headers = new HttpHeaders();
				headers.putAll(exchange.getRequest().getHeaders());

				// the new content type will be computed by bodyInserter
				// and then set in the request decorator
				// 移除Content-Length头信息
				headers.remove(HttpHeaders.CONTENT_LENGTH);

				// if the body is changing content types, set it here, to the bodyInserter
				// will know about it
				// 如果Content-Type数据不为空则会向头信息中设置Content-Type数据
				if (config.getContentType() != null) {
					headers.set(HttpHeaders.CONTENT_TYPE, config.getContentType());
				}
				// 构造写出对象
				CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange,
						headers);
				// 写出信息
				return bodyInserter.insert(outputMessage, new BodyInserterContext())
						.then(Mono.defer(() -> {
							// 对请求进行装饰得到新的请求对象
							ServerHttpRequest decorator = decorate(exchange, headers,
									outputMessage);
							// 交给下一个过滤器处理
							return chain.filter(exchange.mutate().request(decorator).build());
						})).onErrorResume(
								(Function<Throwable, Mono<Void>>) throwable -> release(exchange,
										outputMessage, throwable));
			}

			@Override
			public String toString() {
				return filterToStringCreator(ModifyRequestBodyGatewayFilterFactory.this)
						.append("Content type", config.getContentType()).append("In class", config.getInClass())
						.append("Out class", config.getOutClass()).toString();
			}
		};
	}

	protected Mono<Void> release(ServerWebExchange exchange, CachedBodyOutputMessage outputMessage,
			Throwable throwable) {
		if (outputMessage.isCached()) {
			return outputMessage.getBody().map(DataBufferUtils::release).then(Mono.error(throwable));
		}
		return Mono.error(throwable);
	}

	ServerHttpRequestDecorator decorate(ServerWebExchange exchange, HttpHeaders headers,
			CachedBodyOutputMessage outputMessage) {
		// 创建包装对象
		return new ServerHttpRequestDecorator(exchange.getRequest()) {
			@Override
			public HttpHeaders getHeaders() {
				// 获取内容长度
				long contentLength = headers.getContentLength();
				// 创建头信息
				HttpHeaders httpHeaders = new HttpHeaders();
				// 设置参数传递的头信息
				httpHeaders.putAll(headers);
				// 如果内容长度大于0则设置长度，反之设置头信息Transfer-Encoding的数据为chunked
				if (contentLength > 0) {
					httpHeaders.setContentLength(contentLength);
				} else {
					// TODO: this causes a 'HTTP/1.1 411 Length Required' // on
					// httpbin.org
					httpHeaders.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
				}
				return httpHeaders;
			}

			// 从写出消息对象中获取请求体
			@Override
			public Flux<DataBuffer> getBody() {
				return outputMessage.getBody();
			}
		};
	}

	public static class Config {

		private Class inClass;

		private Class outClass;

		private String contentType;

		private RewriteFunction rewriteFunction;

		public Class getInClass() {
			return inClass;
		}

		public Config setInClass(Class inClass) {
			this.inClass = inClass;
			return this;
		}

		public Class getOutClass() {
			return outClass;
		}

		public Config setOutClass(Class outClass) {
			this.outClass = outClass;
			return this;
		}

		public RewriteFunction getRewriteFunction() {
			return rewriteFunction;
		}

		public Config setRewriteFunction(RewriteFunction rewriteFunction) {
			this.rewriteFunction = rewriteFunction;
			return this;
		}

		public <T, R> Config setRewriteFunction(Class<T> inClass, Class<R> outClass,
				RewriteFunction<T, R> rewriteFunction) {
			setInClass(inClass);
			setOutClass(outClass);
			setRewriteFunction(rewriteFunction);
			return this;
		}

		public String getContentType() {
			return contentType;
		}

		public Config setContentType(String contentType) {
			this.contentType = contentType;
			return this;
		}

	}

}
