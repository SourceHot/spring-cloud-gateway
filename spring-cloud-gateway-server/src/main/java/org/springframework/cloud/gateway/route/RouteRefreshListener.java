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

package org.springframework.cloud.gateway.route;

import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.cloud.client.discovery.event.HeartbeatMonitor;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.cloud.client.discovery.event.ParentHeartbeatEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.util.Assert;

/**
 * 路由刷新监听器
 */
// see ZuulDiscoveryRefreshListener
// TODO: make abstract class in commons?
public class RouteRefreshListener implements ApplicationListener<ApplicationEvent> {

	private final ApplicationEventPublisher publisher;

	private HeartbeatMonitor monitor = new HeartbeatMonitor();

	public RouteRefreshListener(ApplicationEventPublisher publisher) {
		Assert.notNull(publisher, "publisher may not be null");
		this.publisher = publisher;
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		// 判断事件类型是否是ContextRefreshedEvent
		if (event instanceof ContextRefreshedEvent) {
			ContextRefreshedEvent refreshedEvent = (ContextRefreshedEvent) event;
			// 判断是否存在名为management的服务命名空间，如果不存在则执行重置方法
			if (!WebServerApplicationContext.hasServerNamespace(
					refreshedEvent.getApplicationContext(), "management")) {
				// 重置
				reset();
			}
		}
		// 如果事件类型是RefreshScopeRefreshedEvent或者InstanceRegisteredEvent则执行重置方法
		else if (event instanceof RefreshScopeRefreshedEvent
				|| event instanceof InstanceRegisteredEvent) {
			reset();
		}
		// 如果事件类型是ParentHeartbeatEvent
		else if (event instanceof ParentHeartbeatEvent) {
			ParentHeartbeatEvent e = (ParentHeartbeatEvent) event;
			resetIfNeeded(e.getValue());
		}
		// 如果事件类型是HeartbeatEvent
		else if (event instanceof HeartbeatEvent) {
			HeartbeatEvent e = (HeartbeatEvent) event;
			resetIfNeeded(e.getValue());
		}
	}

	private void resetIfNeeded(Object value) {
		// 更新心跳检测数据再重置
		if (this.monitor.update(value)) {
			reset();
		}
	}

	private void reset() {
		this.publisher.publishEvent(new RefreshRoutesEvent(this));
	}

}
