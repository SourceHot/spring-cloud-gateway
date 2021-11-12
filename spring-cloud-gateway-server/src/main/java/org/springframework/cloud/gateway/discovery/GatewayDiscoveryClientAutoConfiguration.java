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

package org.springframework.cloud.gateway.discovery;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.client.discovery.composite.CompositeDiscoveryClientAutoConfiguration;
import org.springframework.cloud.gateway.config.GatewayAutoConfiguration;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.DispatcherHandler;

import static org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory.REGEXP_KEY;
import static org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory.REPLACEMENT_KEY;
import static org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory.PATTERN_KEY;
import static org.springframework.cloud.gateway.support.NameUtils.normalizeFilterFactoryName;
import static org.springframework.cloud.gateway.support.NameUtils.normalizeRoutePredicateName;

/**
 * @author Spencer Gibb
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.cloud.gateway.enabled", matchIfMissing = true)
@AutoConfigureBefore(GatewayAutoConfiguration.class)
@AutoConfigureAfter(CompositeDiscoveryClientAutoConfiguration.class)
@ConditionalOnClass({ DispatcherHandler.class, CompositeDiscoveryClientAutoConfiguration.class })
@EnableConfigurationProperties
public class GatewayDiscoveryClientAutoConfiguration {

	/**
	 * 初始化谓词集合
	 */
	public static List<PredicateDefinition> initPredicates() {
		ArrayList<PredicateDefinition> definitions = new ArrayList<>();
		PredicateDefinition predicate = new PredicateDefinition();
		predicate.setName(normalizeRoutePredicateName(PathRoutePredicateFactory.class));
		predicate.addArg(PATTERN_KEY, "'/'+serviceId+'/**'");
		definitions.add(predicate);
		return definitions;
	}

	/**
	 * 初始化过滤器定义集合
	 */
	public static List<FilterDefinition> initFilters() {
		ArrayList<FilterDefinition> definitions = new ArrayList<>();

		// add a filter that removes /serviceId by default
		FilterDefinition filter = new FilterDefinition();
		filter.setName(normalizeFilterFactoryName(RewritePathGatewayFilterFactory.class));
		String regex = "'/' + serviceId + '/?(?<remaining>.*)'";
		String replacement = "'/${remaining}'";
		filter.addArg(REGEXP_KEY, regex);
		filter.addArg(REPLACEMENT_KEY, replacement);
		definitions.add(filter);

		return definitions;
	}

	/**
	 * 服务发现用的定位器属性
	 * @return
	 */
	@Bean
	public DiscoveryLocatorProperties discoveryLocatorProperties() {
		DiscoveryLocatorProperties properties = new DiscoveryLocatorProperties();
		properties.setPredicates(initPredicates());
		properties.setFilters(initFilters());
		return properties;
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(value = "spring.cloud.discovery.reactive.enabled", matchIfMissing = true)
	public static class ReactiveDiscoveryClientRouteDefinitionLocatorConfiguration {

		/**
		 * 服务发现路由定位器
		 */
		@Bean
		@ConditionalOnProperty(name = "spring.cloud.gateway.discovery.locator.enabled")
		public DiscoveryClientRouteDefinitionLocator discoveryClientRouteDefinitionLocator(
				ReactiveDiscoveryClient discoveryClient, DiscoveryLocatorProperties properties) {
			return new DiscoveryClientRouteDefinitionLocator(discoveryClient, properties);
		}

	}

}
