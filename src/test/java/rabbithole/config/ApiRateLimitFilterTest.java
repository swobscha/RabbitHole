package rabbithole.config;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import rabbithole.service.SpotifyAuthService;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRateLimitFilterTest {

    @Test
    void generationsAreCappedSiteWideEvenAcrossDifferentVisitors() {
        SpotifyAuthService auth = Mockito.mock(SpotifyAuthService.class);
        Mockito.when(auth.findSession(Mockito.any())).thenReturn(Mono.empty());

        ApiRateLimitFilter filter = new ApiRateLimitFilter(auth, 0, 2, 2);

        // Each visitor is far under their own limit; the third still hits the shared hourly cap.
        assertNull(generateFrom(filter, "10.0.0.1").getResponse().getStatusCode());
        assertNull(generateFrom(filter, "10.0.0.2").getResponse().getStatusCode());

        MockServerWebExchange third = generateFrom(filter, "10.0.0.3");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getResponse().getStatusCode());
        assertTrue(third.getResponse().getBodyAsString().block().contains("hourly limit"));
    }

    @Test
    void eachVisitorGetsOnlySixPrefetchesAMinute() {
        SpotifyAuthService auth = Mockito.mock(SpotifyAuthService.class);
        Mockito.when(auth.findSession(Mockito.any())).thenReturn(Mono.empty());

        ApiRateLimitFilter filter = new ApiRateLimitFilter(auth, 0, 1000, 1000);

        for (int i = 0; i < 6; i++) {
            assertNull(postFrom(filter, "/api/playlists/prefetch", "10.0.0.9").getResponse().getStatusCode());
        }

        MockServerWebExchange seventh = postFrom(filter, "/api/playlists/prefetch", "10.0.0.9");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, seventh.getResponse().getStatusCode());
    }

    private MockServerWebExchange generateFrom(ApiRateLimitFilter filter, String ip) {
        return postFrom(filter, "/api/playlists/generate", ip);
    }

    private MockServerWebExchange postFrom(ApiRateLimitFilter filter, String path, String ip) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(path)
                        .remoteAddress(new InetSocketAddress(ip, 5000))
                        .build()
        );

        filter.filter(exchange, ignored -> Mono.empty()).block();

        return exchange;
    }
}
