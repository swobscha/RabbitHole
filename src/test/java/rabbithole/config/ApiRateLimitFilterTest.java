package rabbithole.config;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import rabbithole.service.SpotifyAuthService;
import rabbithole.service.UsageCounterStore;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRateLimitFilterTest {

    @Test
    void generationsAreCappedSiteWideEvenAcrossDifferentVisitors() {
        ApiRateLimitFilter filter = filter(noSavedCounts(), 0, false, 2, 2, 2);

        // Each visitor is far under their own limit; the third still hits the shared hourly cap.
        assertNull(postFrom(filter, "/api/playlists/generate", "10.0.0.1").getResponse().getStatusCode());
        assertNull(postFrom(filter, "/api/playlists/generate", "10.0.0.2").getResponse().getStatusCode());

        MockServerWebExchange third = postFrom(filter, "/api/playlists/generate", "10.0.0.3");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getResponse().getStatusCode());
        assertTrue(third.getResponse().getBodyAsString().block().contains("hourly limit"));
    }

    @Test
    void artistSearchesAreCappedSiteWide() {
        ApiRateLimitFilter filter = filter(noSavedCounts(), 0, false, 1000, 1000, 2);

        assertNull(searchFrom(filter, "10.0.0.1").getResponse().getStatusCode());
        assertNull(searchFrom(filter, "10.0.0.2").getResponse().getStatusCode());

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, searchFrom(filter, "10.0.0.3").getResponse().getStatusCode());
    }

    @Test
    void anHourlyCountSavedBeforeARestartStillCounts() {
        UsageCounterStore store = Mockito.mock(UsageCounterStore.class);
        Mockito.when(store.load("site:")).thenReturn(List.of(
                new UsageCounterStore.SavedCount("site:generate-playlist", System.currentTimeMillis() - 60_000, 2)
        ));

        ApiRateLimitFilter filter = filter(store, 0, false, 2, 2, 2);
        filter.loadSavedSiteWideCounts();

        MockServerWebExchange first = postFrom(filter, "/api/playlists/generate", "10.0.0.1");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, first.getResponse().getStatusCode());
    }

    @Test
    void eachHourlyCountIsSavedSoARestartKeepsIt() {
        UsageCounterStore store = noSavedCounts();
        ApiRateLimitFilter filter = filter(store, 0, false, 5, 5, 5);

        postFrom(filter, "/api/playlists/generate", "10.0.0.1");
        postFrom(filter, "/api/playlists/generate", "10.0.0.2");

        Mockito.verify(store).save(Mockito.eq("site:generate-playlist"), Mockito.anyLong(), Mockito.eq(2));
    }

    @Test
    void eachVisitorGetsOnlySixPrefetchesAMinute() {
        ApiRateLimitFilter filter = filter(noSavedCounts(), 0, false, 1000, 1000, 1000);

        for (int i = 0; i < 6; i++) {
            assertNull(postFrom(filter, "/api/playlists/prefetch", "10.0.0.9").getResponse().getStatusCode());
        }

        MockServerWebExchange seventh = postFrom(filter, "/api/playlists/prefetch", "10.0.0.9");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, seventh.getResponse().getStatusCode());
    }

    @Test
    void onRenderVisitorsAreToldApartByTheAddressRendersProxyAdds() {
        // RATE_LIMIT_TRUSTED_PROXY_HOPS isn't set, but RENDER=true is.
        ApiRateLimitFilter filter = filter(noSavedCounts(), null, true, 1000, 1000, 1000);

        // Every request arrives from Render's proxy; the proxy puts the visitor's address last.
        for (int i = 0; i < 5; i++) {
            assertNull(generateViaProxy(filter, "198.51.100.7").getResponse().getStatusCode());
        }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, generateViaProxy(filter, "198.51.100.7").getResponse().getStatusCode());

        // A different visitor behind the same proxy has their own limit.
        assertNull(generateViaProxy(filter, "203.0.113.9").getResponse().getStatusCode());
    }

    private static ApiRateLimitFilter filter(
            UsageCounterStore store,
            Integer proxyHops,
            boolean onRender,
            int generationsPerHour,
            int replacementsPerHour,
            int artistSearchesPerHour
    ) {
        SpotifyAuthService auth = Mockito.mock(SpotifyAuthService.class);
        Mockito.when(auth.findSession(Mockito.any())).thenReturn(Mono.empty());

        return new ApiRateLimitFilter(
                auth, store, proxyHops, onRender, generationsPerHour, replacementsPerHour, artistSearchesPerHour
        );
    }

    private static UsageCounterStore noSavedCounts() {
        UsageCounterStore store = Mockito.mock(UsageCounterStore.class);
        Mockito.when(store.load(Mockito.anyString())).thenReturn(List.of());
        return store;
    }

    private MockServerWebExchange postFrom(ApiRateLimitFilter filter, String path, String ip) {
        return run(filter, MockServerHttpRequest.post(path).remoteAddress(new InetSocketAddress(ip, 5000)).build());
    }

    private MockServerWebExchange searchFrom(ApiRateLimitFilter filter, String ip) {
        return run(filter, MockServerHttpRequest.get("/api/spotify/artists/search?query=x")
                .remoteAddress(new InetSocketAddress(ip, 5000))
                .build());
    }

    private MockServerWebExchange generateViaProxy(ApiRateLimitFilter filter, String visitorIp) {
        return run(filter, MockServerHttpRequest.post("/api/playlists/generate")
                .remoteAddress(new InetSocketAddress("10.10.0.5", 5000))
                // Anything a visitor sends themselves comes first and is ignored.
                .header("X-Forwarded-For", "1.1.1.1, " + visitorIp)
                .build());
    }

    private MockServerWebExchange run(ApiRateLimitFilter filter, MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ignored -> Mono.empty()).block();

        return exchange;
    }
}
