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

package org.springframework.cloud.gateway.support.tagsprovider;

import io.micrometer.core.instrument.Tags;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;

/**
 * 网关 Http 标签提供程序
 * @author Ingyu Hwang
 */
public class GatewayHttpTagsProvider implements GatewayTagsProvider {

	@Override
	public Tags apply(ServerWebExchange exchange) {
		// 定义outcome、status、httpStatusCodeStr
		String outcome = "CUSTOM";
		String status = "CUSTOM";
		String httpStatusCodeStr = "NA";

		// 提取HTTP方法（GET\POST\DELETE\PUT）
		String httpMethod = exchange.getRequest().getMethodValue();

		// a non standard HTTPS status could be used. Let's be defensive here
		// it needs to be checked for first, otherwise the delegate response
		// who's status DIDN'T change, will be used
		// 如果请求响应是AbstractServerHttpResponse类型
		if (exchange.getResponse() instanceof AbstractServerHttpResponse) {
			// 获取响应码
			Integer statusInt = ((AbstractServerHttpResponse) exchange.getResponse()).getRawStatusCode();
			// 如果响应码
			if (statusInt != null) {
				// 计算状态信息
				status = String.valueOf(statusInt);
				httpStatusCodeStr = status;
				// 将状态码数值转换为HTTP状态对象
				HttpStatus resolved = HttpStatus.resolve(statusInt);
				// 如果状态对象不为空会计算outcome和status
				if (resolved != null) {
					// this is not a CUSTOM status, so use series here.
					outcome = resolved.series().name();
					status = resolved.name();
				}
			}
		}
		// 如果请求响应不是AbstractServerHttpResponse类型
		else {
			// 从响应对象中直接获取HTTP状态对象
			HttpStatus statusCode = exchange.getResponse().getStatusCode();
			// 如果HTTP状态对象不为空将从中提取httpStatusCodeStr、outcome和status
			if (statusCode != null) {
				httpStatusCodeStr = String.valueOf(statusCode.value());
				outcome = statusCode.series().name();
				status = statusCode.name();
			}
		}

		return Tags.of("outcome", outcome, "status", status, "httpStatusCode", httpStatusCodeStr, "httpMethod",
				httpMethod);
	}

}
