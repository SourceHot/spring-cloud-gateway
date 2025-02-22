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

package org.springframework.cloud.gateway.handler.predicate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.style.ToStringCreator;
import org.springframework.http.server.PathContainer;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPattern.PathMatchInfo;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.putUriTemplateVariables;
import static org.springframework.http.server.PathContainer.parsePath;

/**
 * @author Spencer Gibb
 * @author Dhawal Kapil
 */
public class PathRoutePredicateFactory extends AbstractRoutePredicateFactory<PathRoutePredicateFactory.Config> {

	private static final Log log = LogFactory.getLog(PathRoutePredicateFactory.class);

	private static final String MATCH_TRAILING_SLASH = "matchTrailingSlash";

	private PathPatternParser pathPatternParser = new PathPatternParser();

	public PathRoutePredicateFactory() {
		super(Config.class);
	}

	private static void traceMatch(String prefix, Object desired, Object actual, boolean match) {
		if (log.isTraceEnabled()) {
			String message = String.format("%s \"%s\" %s against value \"%s\"", prefix, desired,
					match ? "matches" : "does not match", actual);
			log.trace(message);
		}
	}

	public void setPathPatternParser(PathPatternParser pathPatternParser) {
		this.pathPatternParser = pathPatternParser;
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList("patterns", MATCH_TRAILING_SLASH);
	}

	@Override
	public ShortcutType shortcutType() {
		return ShortcutType.GATHER_LIST_TAIL_FLAG;
	}

	@Override
	public Predicate<ServerWebExchange> apply(Config config) {
		// 创建地址匹配器集合
		final ArrayList<PathPattern> pathPatterns = new ArrayList<>();
		synchronized (this.pathPatternParser) {
			pathPatternParser.setMatchOptionalTrailingSeparator(config.isMatchTrailingSlash());
			// 将配置类中的数据转化为PathPattern对象放入到地址匹配器集合中
			config.getPatterns().forEach(pattern -> {
				PathPattern pathPattern = this.pathPatternParser.parse(pattern);
				pathPatterns.add(pathPattern);
			});
		}
		return new GatewayPredicate() {
			@Override
			public boolean test(ServerWebExchange exchange) {
				// 获取请求地址
				PathContainer path = parsePath(exchange.getRequest().getURI().getRawPath());

				// 在地址匹配器集合中搜索正确的地址匹配器
				PathPattern match = null;
				for (int i = 0; i < pathPatterns.size(); i++) {
					PathPattern pathPattern = pathPatterns.get(i);
					if (pathPattern.matches(path)) {
						match = pathPattern;
						break;
					}
				}

				// 地址匹配器不为空
				if (match != null) {
					traceMatch("Pattern", match.getPatternString(), path, true);
					// 解析地址匹配消息
					PathMatchInfo pathMatchInfo = match.matchAndExtract(path);
					// 将地址匹配消息放入到exchange对象中
					putUriTemplateVariables(exchange, pathMatchInfo.getUriVariables());
					return true;
				}
				// 地址匹配器为空返回false
				else {
					// 日志
					traceMatch("Pattern", config.getPatterns(), path, false);
					return false;
				}
			}

			@Override
			public String toString() {
				return String.format("Paths: %s, match trailing slash: %b", config.getPatterns(),
						config.isMatchTrailingSlash());
			}
		};
	}

	@Validated
	public static class Config {

		private List<String> patterns = new ArrayList<>();

		private boolean matchTrailingSlash = true;

		public List<String> getPatterns() {
			return patterns;
		}

		public Config setPatterns(List<String> patterns) {
			this.patterns = patterns;
			return this;
		}

		/**
		 * @deprecated use {@link #isMatchTrailingSlash()}
		 */
		@Deprecated
		public boolean isMatchOptionalTrailingSeparator() {
			return isMatchTrailingSlash();
		}

		/**
		 * @deprecated use {@link #setMatchTrailingSlash(boolean)}
		 */
		@Deprecated
		public Config setMatchOptionalTrailingSeparator(boolean matchOptionalTrailingSeparator) {
			setMatchTrailingSlash(matchOptionalTrailingSeparator);
			return this;
		}

		public boolean isMatchTrailingSlash() {
			return matchTrailingSlash;
		}

		public Config setMatchTrailingSlash(boolean matchTrailingSlash) {
			this.matchTrailingSlash = matchTrailingSlash;
			return this;
		}

		@Override
		public String toString() {
			return new ToStringCreator(this).append("patterns", patterns)
					.append(MATCH_TRAILING_SLASH, matchTrailingSlash).toString();
		}

	}

}
