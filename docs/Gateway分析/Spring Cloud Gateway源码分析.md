# Spring Cloud Gateway源码分析

## 概述
Spring Cloud Gateway是一个基于WebFlux框架开发的微服务网关项目。

### 特点：
- 非阻塞：默认使用RxNetty作为响应式Web容器，通过非阻塞方式，
利用较少的线程和资源来处理高并发请求，并提升服务资源利用的可伸缩性。
- 函数式编程：通过使用Spring WebFlux的函数式编程模式定义路由端点，处理请求

### 网关的特征
- 路由
- 过滤器

### 核心概念
- Route（路由）：包括Id，目标URL、一组断言、一组过滤器组成。
- Filter（过滤器）：是为了拦截和修改请求
- Predicate（断言）：断言）：Predicate来自Java 8的接口，它可以用来匹配来自HTTP请求的任何内容，例如headers或参数。接口包含多种默认方法，并将Predicate组合成复杂的逻辑（与、或、非），可以用于接口参数校验、路由转发判断等。



## 原理

Spring Cloud Gateway中使用HandlerMapping对请求的链接进行解析，匹配对应的Route，转发到对应的服务。
用户请 求 先 通Spring Cloud Gateway中使用HandlerMapping对请求的链接进行解析，匹配对应的Route，转发到对应的服务。 
请 求 先 通 过 DispatcherHandler 找 到 对 应 的GatewayHandlerMapping，再通过GatewayHandlerMapping解析匹配到的Handler；
Handler处理完后，经过Filter处理，最终将请求转发到后端服务。
过 DispatcherHandler 找 到 对 应 的GatewayHandlerMapping，再通过GatewayHandlerMapping解析匹配到的Handler；Handler处理完后，经过Filter处理，最终将请求转发到后端服务。

## 源码版本
Spring-cloud-gateway-server:2.2.6.RELEASE


## 源码分析
DispatcherHandler
```java
public class DispatcherHandler implements WebHandler, ApplicationContextAware {

    
    @Override
    public Mono<Void> handle(ServerWebExchange exchange) {
        if (this.handlerMappings == null) {
            return createNotFoundError();
        }
        // 迭代handlerMappings
        return Flux.fromIterable(this.handlerMappings)
                // 获取请求处理器
                .concatMap(mapping -> mapping.getHandler(exchange))
                .next()
                .switchIfEmpty(createNotFoundError())
                // 通过SimpleHandlerAdapter 组 件 调 用FilteringWebHandler模块的handler方法
                .flatMap(handler -> invokeHandler(exchange, handler))
                .flatMap(result -> handleResult(exchange, result));
    }
    // 通过SimpleHandlerAdapter 组 件 调 用FilteringWebHandler模块的handler方法
    private Mono<HandlerResult> invokeHandler(ServerWebExchange exchange, Object handler) {
        if (this.handlerAdapters != null) {
            for (HandlerAdapter handlerAdapter : this.handlerAdapters) {
                if (handlerAdapter.supports(handler)) {
                    return handlerAdapter.handle(exchange, handler);
                }
            }
        }
        return Mono.error(new IllegalStateException("No HandlerAdapter: " + handler));
    }
}

```

抽象类AbstractHandlerMapping提供了getHandlerInternal子类来实现查找Handler的具体方法。

```java
public abstract class AbstractHandlerMapping extends ApplicationObjectSupport
        implements HandlerMapping, Ordered, BeanNameAware {
	@Override
	public Mono<Object> getHandler(ServerWebExchange exchange) {
        // 
		return getHandlerInternal(exchange).map(handler -> {
			if (logger.isDebugEnabled()) {
				logger.debug(exchange.getLogPrefix() + "Mapped to " + handler);
			}
			ServerHttpRequest request = exchange.getRequest();
			if (hasCorsConfigurationSource(handler) || CorsUtils.isPreFlightRequest(request)) {
				CorsConfiguration config = (this.corsConfigurationSource != null ? this.corsConfigurationSource.getCorsConfiguration(exchange) : null);
				CorsConfiguration handlerConfig = getCorsConfiguration(handler, exchange);
				config = (config != null ? config.combine(handlerConfig) : handlerConfig);
				if (!this.corsProcessor.process(config, exchange) || CorsUtils.isPreFlightRequest(request)) {
					return REQUEST_HANDLED_HANDLER;
				}
			}
			return handler;
		});
	}
}
```

RoutePredicateHandlerMapping用于匹配具体的路由，并返回 FilteringWebHandler。
RoutePredicateHandlerMapping的getHandlerInternal方法从RouteLocator获取路由，并存放在ServerWebExchange中，返回webFilter对象
```java
public class RoutePredicateHandlerMapping extends AbstractHandlerMapping {

    @Override
    protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
        // don't handle requests on management port if set and different than server port
        if (this.managementPortType == DIFFERENT && this.managementPort != null
                && exchange.getRequest().getURI().getPort() == this.managementPort) {
            return Mono.empty();
        }
        exchange.getAttributes().put(GATEWAY_HANDLER_MAPPER_ATTR, getSimpleName());

        return lookupRoute(exchange)
                // .log("route-predicate-handler-mapping", Level.FINER) //name this
                .flatMap((Function<Route, Mono<?>>) r -> {
                    exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                                "Mapping [" + getExchangeDesc(exchange) + "] to " + r);
                    }

                    exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);
                    return Mono.just(webHandler);
                }).switchIfEmpty(Mono.empty().then(Mono.fromRunnable(() -> {
                    exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
                    if (logger.isTraceEnabled()) {
                        logger.trace("No RouteDefinition found for ["
                                + getExchangeDesc(exchange) + "]");
                    }
                })));
    }

    protected Mono<Route> lookupRoute(ServerWebExchange exchange) {
        return this.routeLocator.getRoutes()
                // individually filter routes so that filterWhen error delaying is not a
                // problem
                .concatMap(route -> Mono.just(route).filterWhen(r -> {
                            // add the current route we are testing
                            exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, r.getId());
                            return r.getPredicate().apply(exchange);
                        })
                        // instead of immediately stopping main flux due to error, log and
                        // swallow it
                        .doOnError(e -> logger.error(
                                "Error applying predicate for route: " + route.getId(),
                                e))
                        .onErrorResume(e -> Mono.empty()))
                // .defaultIfEmpty() put a static Route not found
                // or .switchIfEmpty()
                // .switchIfEmpty(Mono.<Route>empty().log("noroute"))
                .next()
                // TODO: error handling
                .map(route -> {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Route matched: " + route.getId());
                    }
                    validateRoute(route, exchange);
                    return route;
                });

        /*
         * TODO: trace logging if (logger.isTraceEnabled()) {
         * logger.trace("RouteDefinition did not match: " + routeDefinition.getId()); }
         */
    }
}
```

DispatcherHandler 通 过 SimpleHandlerAdapter 组 件 调 用FilteringWebHandler模块的handler方法，
FilteringWebHandler模块接 着 调 用 之 前 在 容 器 中 注 册 的 所 有 Filter ， 处 理 完 毕 后 返 回Response


```java
public class FilteringWebHandler implements WebHandler {
    
	@Override
	public Mono<Void> handle(ServerWebExchange exchange) {
		Route route = exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR);
		List<GatewayFilter> gatewayFilters = route.getFilters();

		List<GatewayFilter> combined = new ArrayList<>(this.globalFilters);
		combined.addAll(gatewayFilters);
		// TODO: needed or cached?
		AnnotationAwareOrderComparator.sort(combined);

		if (logger.isDebugEnabled()) {
			logger.debug("Sorted gatewayFilterFactories: " + combined);
		}
        // 调用Filter
		return new DefaultGatewayFilterChain(combined).filter(exchange);
	}
}
```








