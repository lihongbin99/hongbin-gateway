package io.lihongbin.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping.ManagementPortType.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

@Configuration
public class LoadBalancerOverwriteConfig implements BeanPostProcessor {

    private FilteringWebHandler webHandler;
    private RouteLocator routeLocator;
    private GlobalCorsProperties globalCorsProperties;
    private Environment environment;

    // 反射创建 Route
    private static Field idField;
    private static Field orderField;
    private static Field predicateField;
    private static Field gatewayFiltersField;
    private static Field metadataField;
    private static Constructor<?> constructor;

    public LoadBalancerOverwriteConfig(FilteringWebHandler webHandler,
            RouteLocator routeLocator,
            GlobalCorsProperties globalCorsProperties,
            Environment environment) throws NoSuchFieldException {
        this.webHandler = webHandler;
        this.routeLocator = routeLocator;
        this.globalCorsProperties = globalCorsProperties;
        this.environment = environment;

        // 准备反射对象创建新的 Route
        LoadBalancerOverwriteConfig.idField = getField("id");
        LoadBalancerOverwriteConfig.orderField = getField("order");
        LoadBalancerOverwriteConfig.predicateField = getField("predicate");
        LoadBalancerOverwriteConfig.gatewayFiltersField = getField("gatewayFilters");
        LoadBalancerOverwriteConfig.metadataField = getField("metadata");
        // 创建新的路由
        Constructor<?>[] constructors = Route.class.getDeclaredConstructors();
        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterCount() == 6) {
                constructor.setAccessible(true);
                LoadBalancerOverwriteConfig.constructor = constructor;
                break;
            }
        }
    }

    public static Field getField(String fieldName) throws NoSuchFieldException {
        Field field = Route.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if ("routePredicateHandlerMapping".equals(beanName)) {
            if (bean instanceof RoutePredicateHandlerMapping) {
                System.out.println("替换" + beanName);
                return new MyRoutePredicateHandlerMapping(webHandler, routeLocator,
                        globalCorsProperties, environment);
            }
        }
        return bean;
    }

    public static class MyRoutePredicateHandlerMapping extends AbstractHandlerMapping {

        private final FilteringWebHandler webHandler;

        private final RouteLocator routeLocator;

        private final Integer managementPort;

        private final RoutePredicateHandlerMapping.ManagementPortType managementPortType;

        public MyRoutePredicateHandlerMapping(FilteringWebHandler webHandler,
                                            RouteLocator routeLocator, GlobalCorsProperties globalCorsProperties,
                                            Environment environment) {
            this.webHandler = webHandler;
            this.routeLocator = routeLocator;

            this.managementPort = getPortProperty(environment, "management.server.");
            this.managementPortType = getManagementPortType(environment);
            setOrder(1);
            setCorsConfigurations(globalCorsProperties.getCorsConfigurations());
        }

        private RoutePredicateHandlerMapping.ManagementPortType getManagementPortType(Environment environment) {
            Integer serverPort = getPortProperty(environment, "server.");
            if (this.managementPort != null && this.managementPort < 0) {
                return DISABLED;
            }
            return ((this.managementPort == null
                    || (serverPort == null && this.managementPort.equals(8080))
                    || (this.managementPort != 0 && this.managementPort.equals(serverPort)))
                    ? SAME : DIFFERENT);
        }

        private static Integer getPortProperty(Environment environment, String prefix) {
            return environment.getProperty(prefix + "port", Integer.class);
        }

        @Override
        protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
            if (this.managementPortType == DIFFERENT && this.managementPort != null
                    && exchange.getRequest().getURI().getPort() == this.managementPort) {
                return Mono.empty();
            }
            exchange.getAttributes().put(GATEWAY_HANDLER_MAPPER_ATTR, getSimpleName());

            return lookupRoute(exchange)
                    .flatMap((Function<Route, Mono<?>>) r -> {
                        exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
                        if (logger.isDebugEnabled()) {
                            logger.debug(
                                    "Mapping [" + getExchangeDesc(exchange) + "] to " + r);
                        }

                        // 替换的核心方法
                        Route route = r;
                        try {

                            ServerHttpRequest request = exchange.getRequest();
                            URI srcUrl = request.getURI();
                            int i = srcUrl.getPath().indexOf("/", 1);
                            if (i != -1) {
                                // 创建新的 uri
                                String project = srcUrl.getPath().substring(1, i);
                                int j = srcUrl.getPath().indexOf("/", i + 1);
                                if (j != -1) {
                                    String module = srcUrl.getPath().substring(i + 1, j);

                                    URI uri = new URI(r.getUri().getScheme() + "://" + project + "-" + module);
                                    // 获取旧路由的对象
                                    String id = (String) idField.get(r);
                                    int order = (int) orderField.get(r);
                                    AsyncPredicate predicate = (AsyncPredicate) predicateField.get(r);
                                    List gatewayFilters = (List) gatewayFiltersField.get(r);
                                    Map metadata = (Map) metadataField.get(r);

                                    // 创建新的路由
                                    route = (Route) constructor.newInstance(id, uri, order, predicate, gatewayFilters, metadata);
                                }
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
                        return Mono.just(webHandler);
                    }).switchIfEmpty(Mono.empty().then(Mono.fromRunnable(() -> {
                        exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
                        if (logger.isTraceEnabled()) {
                            logger.trace("No RouteDefinition found for ["
                                    + getExchangeDesc(exchange) + "]");
                        }
                    })));
        }

        @Override
        protected CorsConfiguration getCorsConfiguration(Object handler, ServerWebExchange exchange) { return super.getCorsConfiguration(handler, exchange); }

        private String getExchangeDesc(ServerWebExchange exchange) {
            StringBuilder out = new StringBuilder();
            out.append("Exchange: ");
            out.append(exchange.getRequest().getMethod());
            out.append(" ");
            out.append(exchange.getRequest().getURI());
            return out.toString();
        }

        protected Mono<Route> lookupRoute(ServerWebExchange exchange) {
            return this.routeLocator.getRoutes()
                    .concatMap(route -> Mono.just(route).filterWhen(r -> {
                        exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, r.getId());
                        return r.getPredicate().apply(exchange);
                    })
                            .doOnError(e -> logger.error(
                                    "Error applying predicate for route: " + route.getId(),
                                    e))
                            .onErrorResume(e -> Mono.empty()))
                    .next()
                    .map(route -> {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Route matched: " + route.getId());
                        }
                        validateRoute(route, exchange);
                        return route;
                    });

        }

        @SuppressWarnings("UnusedParameters")
        protected void validateRoute(Route route, ServerWebExchange exchange) { }

        protected String getSimpleName() { return "RoutePredicateHandlerMapping"; }

    }

}
