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

package org.springframework.cloud.gateway.actuate;

import java.net.URI;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * @author Spencer Gibb
 */
public class AbstractGatewayControllerEndpoint implements ApplicationEventPublisherAware {

	private static final Log log = LogFactory.getLog(GatewayControllerEndpoint.class);

	/**
	 * 路由定义加载器
	 */
	protected RouteDefinitionLocator routeDefinitionLocator;

	/**
	 * 全局过滤器
	 */
	protected List<GlobalFilter> globalFilters;

	// TODO change casing in next major release

	/**
	 * 网关过滤器工厂
	 */
	protected List<GatewayFilterFactory> GatewayFilters;

	/**
	 * 路由谓词工厂
	 */
	protected List<RoutePredicateFactory> routePredicates;

	/**
	 * 路由定义编辑器
	 */
	protected RouteDefinitionWriter routeDefinitionWriter;

	/**
	 * 路由定位器
	 */
	protected RouteLocator routeLocator;

	/**
	 * 事件推送器
	 */
	protected ApplicationEventPublisher publisher;

	public AbstractGatewayControllerEndpoint(RouteDefinitionLocator routeDefinitionLocator,
			List<GlobalFilter> globalFilters, List<GatewayFilterFactory> gatewayFilters,
			List<RoutePredicateFactory> routePredicates, RouteDefinitionWriter routeDefinitionWriter,
			RouteLocator routeLocator) {
		this.routeDefinitionLocator = routeDefinitionLocator;
		this.globalFilters = globalFilters;
		this.GatewayFilters = gatewayFilters;
		this.routePredicates = routePredicates;
		this.routeDefinitionWriter = routeDefinitionWriter;
		this.routeLocator = routeLocator;
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	// TODO: Add uncommited or new but not active routes endpoint

	/**
	 * 推送路由刷新事件，进行路由刷新操作
	 * @return
	 */
	@PostMapping("/refresh")
	public Mono<Void> refresh() {
		this.publisher.publishEvent(new RefreshRoutesEvent(this));
		return Mono.empty();
	}

	/**
	 * 用于获取全局过滤器
	 * @return
	 */
	@GetMapping("/globalfilters")
	public Mono<HashMap<String, Object>> globalfilters() {
		return getNamesToOrders(this.globalFilters);
	}

	/**
	 * 获取网关过滤器工厂映射
	 * @return
	 */
	@GetMapping("/routefilters")
	public Mono<HashMap<String, Object>> routefilers() {
		return getNamesToOrders(this.GatewayFilters);
	}

	/**
	 * 获取路由谓词工厂映射
	 * @return
	 */
	@GetMapping("/routepredicates")
	public Mono<HashMap<String, Object>> routepredicates() {
		return getNamesToOrders(this.routePredicates);
	}

	private <T> Mono<HashMap<String, Object>> getNamesToOrders(List<T> list) {
		return Flux.fromIterable(list).reduce(new HashMap<>(), this::putItem);
	}

	private HashMap<String, Object> putItem(HashMap<String, Object> map, Object o) {
		Integer order = null;
		if (o instanceof Ordered) {
			order = ((Ordered) o).getOrder();
		}
		// filters.put(o.getClass().getName(), order);
		map.put(o.toString(), order);
		return map;
	}

	/*
	 * http POST :8080/admin/gateway/routes/apiaddreqhead uri=http://httpbin.org:80
	 * predicates:='["Host=**.apiaddrequestheader.org", "Path=/headers"]'
	 * filters:='["AddRequestHeader=X-Request-ApiFoo, ApiBar"]'
	 */

	/**
	 * 保存路由信息
	 * @param id
	 * @param route
	 * @return
	 */
	@PostMapping("/routes/{id}")
	@SuppressWarnings("unchecked")
	public Mono<ResponseEntity<Object>> save(@PathVariable String id, @RequestBody RouteDefinition route) {

		return Mono.just(route).filter(this::validateRouteDefinition)
				.flatMap(routeDefinition -> this.routeDefinitionWriter.save(Mono.just(routeDefinition).map(r -> {
					r.setId(id);
					log.debug("Saving route: " + route);
					return r;
				})).then(Mono.defer(() -> Mono.just(ResponseEntity.created(URI.create("/routes/" + id)).build()))))
				.switchIfEmpty(Mono.defer(() -> Mono.just(ResponseEntity.badRequest().build())));
	}

	private boolean validateRouteDefinition(RouteDefinition routeDefinition) {
		// 过滤器是否有效
		boolean hasValidFilterDefinitions = routeDefinition.getFilters().stream()
				.allMatch(filterDefinition -> GatewayFilters.stream().anyMatch(
						gatewayFilterFactory -> filterDefinition.getName()
								.equals(gatewayFilterFactory.name())));

		// 谓词是否有效
		boolean hasValidPredicateDefinitions = routeDefinition.getPredicates().stream()
				.allMatch(predicateDefinition -> routePredicates.stream()
						.anyMatch(routePredicate -> predicateDefinition.getName()
								.equals(routePredicate.name())));
		log.debug("FilterDefinitions valid: " + hasValidFilterDefinitions);
		log.debug("PredicateDefinitions valid: " + hasValidPredicateDefinitions);
		return hasValidFilterDefinitions && hasValidPredicateDefinitions;
	}

	/**
	 * 根据路由id删除路由信息
	 */
	@DeleteMapping("/routes/{id}")
	public Mono<ResponseEntity<Object>> delete(@PathVariable String id) {
		return this.routeDefinitionWriter.delete(Mono.just(id))
				.then(Mono.defer(() -> Mono.just(ResponseEntity.ok().build())))
				.onErrorResume(t -> t instanceof NotFoundException, t -> Mono.just(ResponseEntity.notFound().build()));
	}

	/**
	 * 根据路由id获取路由信息
	 */
	@GetMapping("/routes/{id}/combinedfilters")
	public Mono<HashMap<String, Object>> combinedfilters(@PathVariable String id) {
		// TODO: missing global filters
		return this.routeLocator.getRoutes().filter(route -> route.getId().equals(id)).reduce(new HashMap<>(),
				this::putItem);
	}

}
