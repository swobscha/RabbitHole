package rabbithole.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import rabbithole.service.SpotifyAuthService;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(-100)
public class ApiRateLimitFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitFilter.class);

    private static final long WINDOW_MS = 60_000L;
    private static final long HOUR_MS = 3_600_000L;
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    // Shared by every visitor: Spotify limits searches for the whole app, not per person.
    private final Map<String, WindowCounter> siteWideCounters = new ConcurrentHashMap<>();

    private final SpotifyAuthService spotifyAuthService;
    private final int trustedProxyHops;
    private final int siteWideGenerationsPerHour;
    private final int siteWideReplacementsPerHour;

    private volatile long lastCleanupMs = System.currentTimeMillis();

    public ApiRateLimitFilter(
            SpotifyAuthService spotifyAuthService,
            @Value("${RATE_LIMIT_TRUSTED_PROXY_HOPS:0}") int trustedProxyHops,
            @Value("${SITE_WIDE_GENERATIONS_PER_HOUR:30}") int siteWideGenerationsPerHour,
            @Value("${SITE_WIDE_REPLACEMENTS_PER_HOUR:120}") int siteWideReplacementsPerHour
    ) {
        this.spotifyAuthService = spotifyAuthService;
        this.trustedProxyHops = trustedProxyHops;
        this.siteWideGenerationsPerHour = siteWideGenerationsPerHour;
        this.siteWideReplacementsPerHour = siteWideReplacementsPerHour;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        RouteLimit routeLimit = routeLimitFor(exchange);

        if (routeLimit == null) {
            return chain.filter(exchange);
        }

        cleanupOldCountersIfNeeded();

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        return spotifyAuthService.findSession(authorization)
                .map(session -> "user:" + session.spotifyUserId())
                .onErrorResume(error -> Mono.empty())
                .defaultIfEmpty("ip:" + clientIp(exchange))
                .flatMap(client -> applyLimit(exchange, chain, routeLimit, client));
    }

    private Mono<Void> applyLimit(
            ServerWebExchange exchange,
            WebFilterChain chain,
            RouteLimit routeLimit,
            String client
    ) {
        String key = client + "|" + routeLimit.name();

        WindowCounter counter = counters.computeIfAbsent(
                key,
                ignored -> new WindowCounter(System.currentTimeMillis())
        );

        boolean allowed = counter.tryConsume(routeLimit.maxRequests(), WINDOW_MS);

        if (!allowed) {
            long retryAfterSeconds = counter.retryAfterSeconds(WINDOW_MS);

            log.warn(
                    "Rate limit reached | route={} | client={} | maxRequestsPerMinute={} | retryAfterSeconds={}",
                    routeLimit.name(),
                    client,
                    routeLimit.maxRequests(),
                    retryAfterSeconds
            );

            return writeRateLimitResponse(
                    exchange,
                    routeLimit,
                    retryAfterSeconds,
                    "Too many requests. Wait about " + retryAfterSeconds + " seconds and try again."
            );
        }

        if (routeLimit.siteWidePerHour() > 0) {
            WindowCounter siteWide = siteWideCounters.computeIfAbsent(
                    routeLimit.name(),
                    ignored -> new WindowCounter(System.currentTimeMillis())
            );

            if (!siteWide.tryConsume(routeLimit.siteWidePerHour(), HOUR_MS)) {
                long retryAfterSeconds = siteWide.retryAfterSeconds(HOUR_MS);
                long minutes = Math.max(1, Math.round(retryAfterSeconds / 60.0));

                log.warn(
                        "Site-wide hourly limit reached | route={} | maxPerHour={} | retryAfterSeconds={}",
                        routeLimit.name(),
                        routeLimit.siteWidePerHour(),
                        retryAfterSeconds
                );

                return writeRateLimitResponse(
                        exchange,
                        routeLimit,
                        retryAfterSeconds,
                        "RabbitHole has reached its hourly limit, which keeps it within Spotify's limits. "
                                + "Try again in about " + minutes + (minutes == 1 ? " minute." : " minutes.")
                );
            }
        }

        return chain.filter(exchange);
    }

    private RouteLimit routeLimitFor(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getPath().value();

        if (method == HttpMethod.POST && path.equals("/api/playlists/generate")) {
            return new RouteLimit("generate-playlist", 5, siteWideGenerationsPerHour);
        }

        if (method == HttpMethod.GET && path.equals("/api/playlists/demo")) {
            return new RouteLimit("generate-playlist", 5, siteWideGenerationsPerHour);
        }

        if (method == HttpMethod.POST && path.equals("/api/playlists/prefetch")) {
            return new RouteLimit("prefetch", 20, 0);
        }

        if (method == HttpMethod.POST && path.equals("/api/playlists/replace-track")) {
            return new RouteLimit("replace-track", 15, siteWideReplacementsPerHour);
        }

        if (method == HttpMethod.POST && path.equals("/api/playlists/create")) {
            return new RouteLimit("create-playlist", 10, 0);
        }

        if (method == HttpMethod.GET && path.equals("/api/spotify/artists/search")) {
            return new RouteLimit("artist-search", 30, 0);
        }

        if (method == HttpMethod.GET && path.equals("/api/spotify/login")) {
            return new RouteLimit("spotify-login", 30, 0);
        }

        if (method == HttpMethod.DELETE && path.equals("/api/playlists/history")) {
            return new RouteLimit("clear-history", 5, 0);
        }

        return null;
    }

    private Mono<Void> writeRateLimitResponse(
            ServerWebExchange exchange,
            RouteLimit routeLimit,
            long retryAfterSeconds,
            String message
    ) {
        String path = exchange.getRequest().getPath().value();

        String body = "{"
                + "\"status\":429,"
                + "\"error\":\"Too Many Requests\","
                + "\"message\":\""
                + escapeJson(message)
                + "\","
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

    /**
     * Clients can put anything in X-Forwarded-For, so only the entries added by the configured
     * number of trusted proxies, counted from the right, are believed.
     */
    private String clientIp(ServerWebExchange exchange) {
        if (trustedProxyHops > 0) {
            List<String> forwardedFor = exchange.getRequest()
                    .getHeaders()
                    .getValuesAsList("X-Forwarded-For");

            if (forwardedFor.size() >= trustedProxyHops) {
                return forwardedFor.get(forwardedFor.size() - trustedProxyHops).trim();
            }
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

    /** maxRequests is per visitor per minute; siteWidePerHour is shared by everyone, or 0 for none. */
    private record RouteLimit(
            String name,
            int maxRequests,
            int siteWidePerHour
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