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

import org.springframework.cloud.gateway.route.Route;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * 网关路由标签提供程序
 * @author Ingyu Hwang
 */
public class GatewayRouteTagsProvider implements GatewayTagsProvider {

	@Override
	public Tags apply(ServerWebExchange exchange) {
		Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);

		if (route != null) {
			return Tags.of("routeId", route.getId(), "routeUri", route.getUri().toString());
		}

		return Tags.empty();
	}

}
