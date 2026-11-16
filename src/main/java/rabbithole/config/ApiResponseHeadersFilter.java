package rabbithole.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * API responses can hold a visitor's own data (their playlist history, or a login token in a
 * redirect), so they're never cached, and browsers are told not to guess a different content type.
 * Set before anything else runs, so rate-limit refusals carry them too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiResponseHeadersFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/api/")) {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.setCacheControl("no-store");
            headers.set("X-Content-Type-Options", "nosniff");
        }

        return chain.filter(exchange);
    }
}
