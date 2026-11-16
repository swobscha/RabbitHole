package rabbithole.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
import rabbithole.service.SpotifyRateLimitGuard;
import rabbithole.service.UsageCounterStore;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
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

    private static final String SITE_WIDE_PREFIX = "site:";

    // All API requests from one address, checked before anything else. Generous for a real visitor.
    private static final int MAX_API_REQUESTS_PER_MINUTE = 120;
    private static final int SITE_WIDE_PREFETCHES_PER_HOUR = 60;

    // Only accounts approved in Spotify's dashboard can log in, so real logins are rare.
    @Value("${SITE_WIDE_LOGINS_PER_HOUR:30}")
    private int siteWideLoginsPerHour = 30;

    @Value("${FRONTEND_URL:http://localhost:5173}")
    private String frontendUrl;

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    // Shared by every visitor: Spotify limits searches for the whole app, not per person.
    // Saved, so a deploy doesn't hand out a fresh hour's worth.
    private final Map<String, WindowCounter> siteWideCounters = new ConcurrentHashMap<>();

    private final SpotifyAuthService spotifyAuthService;
    private final UsageCounterStore usageStore;
    private final boolean onRender;
    private final int trustedProxyHops;
    private final int siteWideGenerationsPerHour;
    private final int siteWideReplacementsPerHour;
    private final int siteWideArtistSearchesPerHour;

    private volatile long lastCleanupMs = System.currentTimeMillis();

    /**
     * The hourly defaults keep a busy hour well inside the daily Spotify allowance
     * (SPOTIFY_DAILY_REQUEST_LIMIT): a playlist makes up to about 30 Spotify searches, a replacement
     * up to 8, and an artist search 1.
     *
     * @param configuredProxyHops RATE_LIMIT_TRUSTED_PROXY_HOPS, or null when it isn't set
     * @param onRender Render sets RENDER=true on every service. Render runs behind Cloudflare, which
     *                 puts the visitor's address in CF-Connecting-IP, replacing anything the visitor
     *                 sent. X-Forwarded-For there reads "visitor, Cloudflare server", so its last
     *                 entry is Cloudflare's and changes from request to request.
     */
    public ApiRateLimitFilter(
            SpotifyAuthService spotifyAuthService,
            UsageCounterStore usageStore,
            @Value("${RATE_LIMIT_TRUSTED_PROXY_HOPS:#{null}}") Integer configuredProxyHops,
            @Value("${RENDER:false}") boolean onRender,
            @Value("${SITE_WIDE_GENERATIONS_PER_HOUR:15}") int siteWideGenerationsPerHour,
            @Value("${SITE_WIDE_REPLACEMENTS_PER_HOUR:40}") int siteWideReplacementsPerHour,
            @Value("${SITE_WIDE_ARTIST_SEARCHES_PER_HOUR:60}") int siteWideArtistSearchesPerHour
    ) {
        this.spotifyAuthService = spotifyAuthService;
        this.usageStore = usageStore;
        this.onRender = onRender;
        this.trustedProxyHops = configuredProxyHops != null ? configuredProxyHops : (onRender ? 2 : 0);
        this.siteWideGenerationsPerHour = siteWideGenerationsPerHour;
        this.siteWideReplacementsPerHour = siteWideReplacementsPerHour;
        this.siteWideArtistSearchesPerHour = siteWideArtistSearchesPerHour;

        String fallback = trustedProxyHops > 0
                ? "X-Forwarded-For, trusting " + trustedProxyHops + " proxy hop(s)"
                : "connection address (RATE_LIMIT_TRUSTED_PROXY_HOPS not set)";

        log.info(
                "Rate limits identify logged-out visitors by {}.",
                onRender ? "CF-Connecting-IP (Render runs behind Cloudflare), otherwise " + fallback : fallback
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadSavedSiteWideCounts() {
        long now = System.currentTimeMillis();

        for (UsageCounterStore.SavedCount saved : usageStore.load(SITE_WIDE_PREFIX)) {
            if (now - saved.windowStart() < HOUR_MS) {
                siteWideCounters.put(
                        saved.name().substring(SITE_WIDE_PREFIX.length()),
                        new WindowCounter(saved.windowStart(), saved.requestCount())
                );
            }
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/")) {
            return chain.filter(exchange);
        }

        cleanupOldCountersIfNeeded();

        String address = "ip:" + clientIp(exchange);

        // Every API request from an address counts here first, before anything can reach the database,
        // so requests with made-up login tokens can't be used to keep it busy.
        WindowCounter anyRequest = counters.computeIfAbsent(
                address + "|any-api",
                ignored -> new WindowCounter(System.currentTimeMillis())
        );

        if (!anyRequest.tryConsume(MAX_API_REQUESTS_PER_MINUTE, WINDOW_MS)) {
            long retryAfterSeconds = anyRequest.retryAfterSeconds(WINDOW_MS);

            log.warn("Rate limit reached | route=any-api | client={} | retryAfterSeconds={}", address, retryAfterSeconds);

            return writeRateLimitResponse(
                    exchange,
                    "any-api",
                    retryAfterSeconds,
                    "Too many requests. Wait about " + retryAfterSeconds + " seconds and try again."
            );
        }

        RouteLimit routeLimit = routeLimitFor(exchange);

        if (routeLimit == null) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        return spotifyAuthService.findSession(authorization)
                .map(session -> "user:" + session.spotifyUserId())
                .onErrorResume(error -> Mono.empty())
                .defaultIfEmpty(address)
                .flatMap(client -> applyLimit(exchange, chain, routeLimit, client));
    }

    private Mono<Void> applyLimit(
            ServerWebExchange exchange,
            WebFilterChain chain,
            RouteLimit routeLimit,
            String client
    ) {
        Mono<Void> request = limitedRequest(exchange, chain, routeLimit, client);

        // Tells the Spotify guard whose request this is, so each visitor's share of the daily
        // allowance can be counted. Finishing a login isn't counted against the visitor.
        return routeLimit.countsSpotifyCallsForVisitor()
                ? request.contextWrite(context -> context.put(SpotifyRateLimitGuard.VISITOR_CONTEXT_KEY, client))
                : request;
    }

    private Mono<Void> limitedRequest(
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
                    routeLimit.name(),
                    retryAfterSeconds,
                    "Too many requests. Wait about " + retryAfterSeconds + " seconds and try again."
            );
        }

        if (routeLimit.siteWidePerHour() > 0) {
            WindowCounter siteWide = siteWideCounters.computeIfAbsent(
                    routeLimit.name(),
                    ignored -> new WindowCounter(System.currentTimeMillis())
            );

            boolean allowedSiteWide = siteWide.tryConsume(routeLimit.siteWidePerHour(), HOUR_MS);

            if (allowedSiteWide) {
                WindowCounter.Snapshot snapshot = siteWide.snapshot();
                usageStore.save(SITE_WIDE_PREFIX + routeLimit.name(), snapshot.windowStartedAtMs(), snapshot.requestCount());
            } else {
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
                        routeLimit.name(),
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

        // Each prefetch makes many Last.fm calls, so a visitor gets only a few a minute, and the site
        // only so many an hour; they're just a head start, so running out costs nothing but speed.
        if (method == HttpMethod.POST && path.equals("/api/playlists/prefetch")) {
            return new RouteLimit("prefetch", 6, SITE_WIDE_PREFETCHES_PER_HOUR);
        }

        if (method == HttpMethod.POST && path.equals("/api/playlists/replace-track")) {
            return new RouteLimit("replace-track", 15, siteWideReplacementsPerHour);
        }

        if (method == HttpMethod.POST && path.equals("/api/playlists/create")) {
            return new RouteLimit("create-playlist", 10, 0);
        }

        if (method == HttpMethod.GET && path.equals("/api/spotify/artists/search")) {
            return new RouteLimit("artist-search", 20, siteWideArtistSearchesPerHour);
        }

        if (method == HttpMethod.GET && path.equals("/api/spotify/login")) {
            return new RouteLimit("spotify-login", 30, 0);
        }

        // Finishing a login asks Spotify who the account is, which uses the daily allowance even when
        // the account turns out not to be approved, so logins are capped for the whole site too.
        if (method == HttpMethod.GET && path.equals("/api/spotify/callback")) {
            return new RouteLimit("spotify-callback", 10, siteWideLoginsPerHour, false);
        }

        if (method == HttpMethod.GET && path.equals("/api/spotify/status")) {
            return new RouteLimit("spotify-status", 60, 0);
        }

        if (method == HttpMethod.POST && path.equals("/api/spotify/logout")) {
            return new RouteLimit("spotify-logout", 10, 0);
        }

        if (method == HttpMethod.GET && path.equals("/api/playlists/history")) {
            return new RouteLimit("history", 30, 0);
        }

        if (method == HttpMethod.DELETE && path.equals("/api/playlists/history")) {
            return new RouteLimit("clear-history", 5, 0);
        }

        return null;
    }

    private Mono<Void> writeRateLimitResponse(
            ServerWebExchange exchange,
            String limitName,
            long retryAfterSeconds,
            String message
    ) {
        String path = exchange.getRequest().getPath().value();

        // Logins are page visits rather than calls from the page, so send the visitor back to the
        // page with a message instead of showing them raw JSON.
        if (path.equals("/api/spotify/login") || path.equals("/api/spotify/callback")) {
            String cleanFrontendUrl = frontendUrl == null || frontendUrl.isBlank()
                    ? "http://localhost:5173"
                    : frontendUrl.trim().replaceAll("/+$", "");

            exchange.getResponse().setStatusCode(HttpStatus.SEE_OTHER);
            exchange.getResponse().getHeaders().setLocation(URI.create(cleanFrontendUrl + "?spotify=error&reason=busy"));
            return exchange.getResponse().setComplete();
        }

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
                + escapeJson(limitName)
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
        if (onRender) {
            String cloudflareVisitor = exchange.getRequest().getHeaders().getFirst("CF-Connecting-IP");

            if (cloudflareVisitor != null && !cloudflareVisitor.isBlank()) {
                return visitorAddress(cloudflareVisitor);
            }
        }

        if (trustedProxyHops > 0) {
            List<String> forwardedFor = exchange.getRequest()
                    .getHeaders()
                    .getValuesAsList("X-Forwarded-For");

            if (forwardedFor.size() >= trustedProxyHops) {
                return visitorAddress(forwardedFor.get(forwardedFor.size() - trustedProxyHops));
            }
        }

        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();

        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }

        return visitorAddress(remoteAddress.getAddress().getHostAddress());
    }

    /**
     * A home or phone connection on IPv6 usually gets a whole /64 block of addresses, so anyone could
     * switch address with every request. The block is what identifies the visitor. IPv4 is unchanged.
     */
    static String visitorAddress(String address) {
        String candidate = address.trim();

        if (!candidate.contains(":")) {
            return candidate;
        }

        String literal = candidate.replaceAll("^\\[|]$", "").replaceAll("%.*$", "");

        try {
            // The brackets make Java read it strictly as an IPv6 literal, never as a host name to look up.
            InetAddress parsed = InetAddress.getByName("[" + literal + "]");

            if (!(parsed instanceof Inet6Address)) {
                // An IPv4 address written in IPv6 form.
                return parsed.getHostAddress();
            }

            byte[] bytes = parsed.getAddress();
            StringBuilder block = new StringBuilder();

            for (int i = 0; i < 8; i += 2) {
                block.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff))).append(':');
            }

            return block.append(":/64").toString();
        } catch (UnknownHostException error) {
            return candidate;
        }
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

    /**
     * maxRequests is per visitor per minute; siteWidePerHour is shared by everyone, or 0 for none.
     * countsSpotifyCallsForVisitor says whether Spotify calls made for the request count toward the
     * visitor's share of the daily allowance.
     */
    private record RouteLimit(
            String name,
            int maxRequests,
            int siteWidePerHour,
            boolean countsSpotifyCallsForVisitor
    ) {
        RouteLimit(String name, int maxRequests, int siteWidePerHour) {
            this(name, maxRequests, siteWidePerHour, true);
        }
    }

    private static class WindowCounter {
        private long windowStartedAtMs;
        private int requestCount;

        private WindowCounter(long windowStartedAtMs) {
            this(windowStartedAtMs, 0);
        }

        private WindowCounter(long windowStartedAtMs, int requestCount) {
            this.windowStartedAtMs = windowStartedAtMs;
            this.requestCount = requestCount;
        }

        private synchronized Snapshot snapshot() {
            return new Snapshot(windowStartedAtMs, requestCount);
        }

        private record Snapshot(long windowStartedAtMs, int requestCount) {
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