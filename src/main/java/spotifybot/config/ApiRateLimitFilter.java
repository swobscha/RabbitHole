package spotifybot.config;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(-100)
public class ApiRateLimitFilter implements WebFilter {

    private static final long WINDOW_MS = 60_000L;
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    private volatile long lastCleanupMs = System.currentTimeMillis();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        RouteLimit routeLimit = routeLimitFor(exchange);

        if (routeLimit == null) {
            return chain.filter(exchange);
        }

        cleanupOldCountersIfNeeded();

        String clientIp = clientIp(exchange);
        String key = clientIp + "|" + routeLimit.name();

        WindowCounter counter = counters.computeIfAbsent(
                key,
                ignored -> new WindowCounter(System.currentTimeMillis())
        );

        boolean allowed = counter.tryConsume(routeLimit.maxRequests(), WINDOW_MS);

        if (allowed) {
            return chain.filter(exchange);
        }

        long retryAfterSeconds = counter.retryAfterSeconds(WINDOW_MS);

        System.out.println(
                "RATE LIMIT BLOCKED"
                        + " | route=" + routeLimit.name()
                        + " | clientIp=" + clientIp
                        + " | maxRequestsPerMinute=" + routeLimit.maxRequests()
                        + " | retryAfterSeconds=" + retryAfterSeconds
        );

        return writeRateLimitResponse(exchange, routeLimit, retryAfterSeconds);
    }

    private RouteLimit routeLimitFor(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getPath().value();

        if (method == HttpMethod.POST && path.equals("/api/playlists/generate")) {
            return new RouteLimit("generate-playlist", 5);
        }

        if (method == HttpMethod.POST && path.equals("/api/playlists/replace-track")) {
            return new RouteLimit("replace-track", 15);
        }

        if (method == HttpMethod.POST && path.equals("/api/playlists/create")) {
            return new RouteLimit("create-playlist", 10);
        }

        if (method == HttpMethod.GET && path.equals("/api/spotify/artists/search")) {
            return new RouteLimit("artist-search", 30);
        }

        if (method == HttpMethod.GET && path.equals("/api/spotify/login")) {
            return new RouteLimit("spotify-login", 10);
        }

        if (method == HttpMethod.DELETE && path.equals("/api/playlists/history")) {
            return new RouteLimit("clear-history", 5);
        }

        return null;
    }

    private Mono<Void> writeRateLimitResponse(
            ServerWebExchange exchange,
            RouteLimit routeLimit,
            long retryAfterSeconds
    ) {
        String path = exchange.getRequest().getPath().value();

        String body = "{"
                + "\"status\":429,"
                + "\"error\":\"Too Many Requests\","
                + "\"message\":\"Too many requests. Wait about "
                + retryAfterSeconds
                + " seconds and try again.\","
                + "\"path\":\""
                + escapeJson(path)
                + "\","
                + "\"timestamp\":\""
                + OffsetDateTime.now(ZoneOffset.UTC)
                + "\","
                + "\"limit\":\""
                + escapeJson(routeLimit.name())
                + "\""
                + "}";

        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse()
                        .bufferFactory()
                        .wrap(bytes)));
    }

    private String clientIp(ServerWebExchange exchange) {
        String forwardedFor = exchange.getRequest()
                .getHeaders()
                .getFirst("X-Forwarded-For");

        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String firstIp = forwardedFor.split(",")[0].trim();

            if (!firstIp.isBlank()) {
                return firstIp;
            }
        }

        String realIp = exchange.getRequest()
                .getHeaders()
                .getFirst("X-Real-IP");

        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();

        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }

        return remoteAddress.getAddress().getHostAddress();
    }

    private void cleanupOldCountersIfNeeded() {
        long now = System.currentTimeMillis();

        if (now - lastCleanupMs < CLEANUP_INTERVAL_MS) {
            return;
        }

        lastCleanupMs = now;

        counters.entrySet().removeIf(entry ->
                now - entry.getValue().windowStartedAtMs() > WINDOW_MS * 3
        );
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private record RouteLimit(
            String name,
            int maxRequests
    ) {
    }

    private static class WindowCounter {
        private long windowStartedAtMs;
        private int requestCount;

        private WindowCounter(long windowStartedAtMs) {
            this.windowStartedAtMs = windowStartedAtMs;
            this.requestCount = 0;
        }

        private synchronized boolean tryConsume(int maxRequests, long windowMs) {
            long now = System.currentTimeMillis();

            if (now - windowStartedAtMs >= windowMs) {
                windowStartedAtMs = now;
                requestCount = 0;
            }

            if (requestCount >= maxRequests) {
                return false;
            }

            requestCount++;
            return true;
        }

        private synchronized long retryAfterSeconds(long windowMs) {
            long now = System.currentTimeMillis();
            long windowEndsAtMs = windowStartedAtMs + windowMs;
            long remainingMs = windowEndsAtMs - now;

            if (remainingMs <= 0) {
                return 1;
            }

            return Math.max(1, (remainingMs + 999) / 1000);
        }

        private synchronized long windowStartedAtMs() {
            return windowStartedAtMs;
        }
    }
}