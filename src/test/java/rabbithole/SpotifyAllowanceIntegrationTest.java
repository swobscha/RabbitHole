package rabbithole;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs with a tiny daily Spotify allowance (20 requests: 3 per logged-out visitor) to show a visitor's
 * share is enforced through the whole app, including song searches that keep running in the background.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.r2dbc.url=r2dbc:h2:mem:///allowance;DB_CLOSE_DELAY=-1",
        "SPOTIFY_CLIENT_ID=test-client",
        "SPOTIFY_CLIENT_SECRET=test-secret",
        "SPOTIFY_REDIRECT_URI=http://127.0.0.1:8080/api/spotify/callback",
        "FRONTEND_URL=http://frontend.test",
        "CORS_ALLOWED_ORIGINS=http://frontend.test",
        "lastfm.api-key=test-key",
        "RATE_LIMIT_TRUSTED_PROXY_HOPS=1",
        "SPOTIFY_DAILY_REQUEST_LIMIT=20",
        "DEBUG_RANKINGS_ENABLED=false"
})
class SpotifyAllowanceIntegrationTest {

    private static final FakeMusicApis FAKE = startFake();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void pointAtFakeApis(DynamicPropertyRegistry registry) {
        registry.add("SPOTIFY_ACCOUNTS_URL", () -> FAKE.url("/accounts"));
        registry.add("SPOTIFY_API_URL", () -> FAKE.url("/v1"));
        registry.add("LASTFM_API_URL", () -> FAKE.url("/lastfm"));
    }

    private static FakeMusicApis startFake() {
        try {
            return new FakeMusicApis();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void oneVisitorCantUseMoreThanTheirShareOfTheDailyAllowance() {
        String visitor = "203.0.113.5";
        int searchesBefore = FAKE.artistSearchRequests.get();

        for (int i = 0; i < 3; i++) {
            search(visitor, "Name " + UUID.randomUUID()).expectStatus().isOk();
        }

        search(visitor, "Name " + UUID.randomUUID())
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.message").value(message -> assertContains(message, "your share"));

        assertEquals(3, FAKE.artistSearchRequests.get() - searchesBefore);

        // Song searches for a playlist are counted for the same visitor, so it's refused too.
        int trackSearchesBefore = FAKE.trackSearchRequests.get();

        client(visitor).post().uri("/api/playlists/generate")
                .bodyValue(generationRequest())
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.message").value(message -> assertContains(message, "your share"));

        assertEquals(trackSearchesBefore, FAKE.trackSearchRequests.get());

        // Someone else still can.
        search("203.0.113.77", "Name " + UUID.randomUUID()).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec search(String visitor, String name) {
        return client(visitor).get()
                .uri(builder -> builder.path("/api/spotify/artists/search").queryParam("query", name).build())
                .exchange();
    }

    private WebTestClient client(String visitor) {
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(60))
                .defaultHeader("X-Forwarded-For", visitor)
                .build();
    }

    private static Map<String, Object> generationRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("seedArtist", "Allowance " + UUID.randomUUID());
        request.put("playlistName", "Mix");
        request.put("playlistSize", 3);
        request.put("restrictedArtists", List.of());
        request.put("preferredArtists", List.of());
        request.put("preferredGenres", List.of());
        request.put("randomness", 0.0);
        request.put("generationSeed", "seed");
        request.put("avoidUsedTracks", false);
        request.put("nicheArtistScale", 0);
        request.put("nicheSongScale", 0.5);
        request.put("similarityScale", 0.5);
        return request;
    }

    private static void assertContains(Object message, String expected) {
        if (!String.valueOf(message).contains(expected)) {
            throw new AssertionError("Expected \"" + expected + "\" in: " + message);
        }
    }
}
