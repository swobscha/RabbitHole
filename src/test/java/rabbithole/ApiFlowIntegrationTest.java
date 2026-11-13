package rabbithole;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseCookie;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;
import rabbithole.model.CreatedPlaylistHistoryItem;
import rabbithole.model.CreatedPlaylistResponse;
import rabbithole.model.PlaylistGenerationResult;
import rabbithole.model.PlaylistReplaceTrackResult;
import rabbithole.model.SpotifySession;
import rabbithole.model.WeightedTrack;
import rabbithole.service.SpotifySessionStore;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.r2dbc.url=r2dbc:h2:mem:///apiflow;DB_CLOSE_DELAY=-1",
        "SPOTIFY_CLIENT_ID=test-client",
        "SPOTIFY_CLIENT_SECRET=test-secret",
        "SPOTIFY_REDIRECT_URI=http://127.0.0.1:8080/api/spotify/callback",
        "FRONTEND_URL=http://frontend.test",
        "CORS_ALLOWED_ORIGINS=http://frontend.test",
        "lastfm.api-key=test-key",
        "RATE_LIMIT_TRUSTED_PROXY_HOPS=0",
        "DEBUG_RANKINGS_ENABLED=false"
})
class ApiFlowIntegrationTest {

    private static final String STATE_COOKIE = "spotify_oauth_state";
    private static final String ALLOWED_ORIGIN = "http://frontend.test";

    private static final FakeMusicApis FAKE = startFake();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private SpotifySessionStore sessionStore;

    @Autowired
    private DatabaseClient databaseClient;

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

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(60))
                .build();
    }

    private static String uniqueCode() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private EntityExchangeResult<byte[]> startLogin() {
        return client().get().uri("/api/spotify/login")
                .exchange()
                .expectStatus().isFound()
                .expectBody()
                .returnResult();
    }

    private URI finishLogin(String code, String state, String cookieValue) {
        WebTestClient.RequestHeadersSpec<?> request = client().get()
                .uri(builder -> builder.path("/api/spotify/callback")
                        .queryParam("code", code)
                        .queryParam("state", state)
                        .build());

        if (cookieValue != null) {
            request = request.cookie(STATE_COOKIE, cookieValue);
        }

        return request.exchange()
                .expectStatus().isFound()
                .expectBody()
                .returnResult()
                .getResponseHeaders()
                .getLocation();
    }

    private static String stateOf(EntityExchangeResult<byte[]> login) {
        return UriComponentsBuilder.fromUri(login.getResponseHeaders().getLocation())
                .build()
                .getQueryParams()
                .getFirst("state");
    }

    private String login(String code) {
        EntityExchangeResult<byte[]> login = startLogin();
        ResponseCookie cookie = login.getResponseCookies().getFirst(STATE_COOKIE);

        assertNotNull(cookie);
        assertTrue(cookie.isHttpOnly());

        URI redirect = finishLogin(code, stateOf(login), cookie.getValue());

        assertEquals("frontend.test", redirect.getHost());
        assertTrue(redirect.getFragment().startsWith("session="), redirect.toString());

        return redirect.getFragment().substring("session=".length());
    }

    private static Map<String, Object> generationRequest(String seed, int size) {
        Map<String, Object> request = new HashMap<>();
        request.put("seedArtist", seed);
        request.put("playlistName", "Mix");
        request.put("playlistSize", size);
        request.put("restrictedArtists", List.of());
        request.put("preferredArtists", List.of());
        request.put("preferredGenres", List.of());
        request.put("randomness", 0.0);
        request.put("generationSeed", "seed");
        request.put("avoidUsedTracks", true);
        request.put("nicheArtistScale", 0);
        request.put("nicheSongScale", 0.5);
        request.put("similarityScale", 0.5);

        return request;
    }

    private PlaylistGenerationResult generate(String token, String seed, int size) {
        return client().post().uri("/api/playlists/generate")
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(generationRequest(seed, size))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PlaylistGenerationResult.class)
                .returnResult()
                .getResponseBody();
    }

    private WebTestClient.ResponseSpec create(String token, String seed, List<WeightedTrack> tracks) {
        return client().post().uri("/api/playlists/create")
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(Map.of(
                        "playlistName", "Mix",
                        "seedArtist", seed,
                        "tracks", tracks,
                        "targetTrackCount", tracks.size()
                ))
                .exchange();
    }

    private List<CreatedPlaylistHistoryItem> history(String token) {
        return client().get().uri("/api/playlists/history")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(CreatedPlaylistHistoryItem.class)
                .returnResult()
                .getResponseBody();
    }

    private boolean connected(String token) {
        Map<?, ?> status = client().get().uri("/api/spotify/status")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        return Boolean.TRUE.equals(status.get("connected"));
    }

    private static Set<String> uris(List<WeightedTrack> tracks) {
        return tracks.stream().map(track -> track.track().spotifyUri()).collect(Collectors.toSet());
    }

    @Test
    void accountEndpointsRejectRequestsWithoutASession() {
        client().get().uri("/api/playlists/history").exchange().expectStatus().isUnauthorized();
        client().delete().uri("/api/playlists/history").exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/playlists/demo").exchange().expectStatus().isUnauthorized();

        client().post().uri("/api/playlists/create")
                .bodyValue(Map.of("playlistName", "Mix"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Spotify is not connected. Connect Spotify and try again.");

        client().get().uri("/api/playlists/history")
                .header("Authorization", "Bearer " + "x".repeat(43))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void loggedOutVisitorsCanSearchGenerateAndReplace() {
        String seed = "Visitor " + uniqueCode();
        int appTokensBefore = FAKE.appTokenRequests.get();

        client().get().uri(builder -> builder.path("/api/spotify/artists/search").queryParam("query", seed).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo(seed);

        Map<String, Object> request = generationRequest(seed, 3);

        List<WeightedTrack> tracks = client().post().uri("/api/playlists/generate")
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(PlaylistGenerationResult.class)
                .returnResult()
                .getResponseBody()
                .tracks();

        assertEquals(3, tracks.size());

        PlaylistReplaceTrackResult replacement = client().post().uri("/api/playlists/replace-track")
                .bodyValue(Map.of(
                        "generationRequest", request,
                        "currentTracks", tracks,
                        "trackToReplace", tracks.get(0)
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PlaylistReplaceTrackResult.class)
                .returnResult()
                .getResponseBody();

        assertFalse(uris(tracks).contains(replacement.replacementTrack().track().spotifyUri()));

        // One app-only token is fetched at most and then reused.
        assertTrue(FAKE.appTokenRequests.get() - appTokensBefore <= 1);
    }

    @Test
    void callbackRejectsAStateThatDoesNotMatchThisBrowser() {
        EntityExchangeResult<byte[]> login = startLogin();
        String state = stateOf(login);

        for (URI redirect : List.of(
                finishLogin(uniqueCode(), state, null),
                finishLogin(uniqueCode(), state, "some-other-state")
        )) {
            assertNull(redirect.getFragment());
            assertTrue(redirect.getQuery().contains("spotify=error"), redirect.toString());
        }
    }

    @Test
    void loginStartedOnLocalhostMovesToTheRedirectHostFirst() {
        URI location = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build()
                .get().uri("/api/spotify/login")
                .exchange()
                .expectStatus().isFound()
                .expectBody()
                .returnResult()
                .getResponseHeaders()
                .getLocation();

        assertEquals("127.0.0.1", location.getHost());
        assertEquals(port, location.getPort());
    }

    @Test
    void eachAccountOnlySeesItsOwnHistory() {
        String seed = "Isolation " + uniqueCode();
        String first = login(uniqueCode());
        String second = login(uniqueCode());

        PlaylistGenerationResult result = generate(first, seed, 3);
        assertEquals(3, result.tracks().size());
        create(first, seed, result.tracks()).expectStatus().isOk();

        assertEquals(1, history(first).size());
        assertTrue(history(second).isEmpty());

        client().delete().uri("/api/playlists/history")
                .headers(headers -> headers.setBearerAuth(second))
                .exchange()
                .expectStatus().isOk();

        assertEquals(1, history(first).size());
    }

    @Test
    void usedTracksAreRememberedAndSkippedPerAccount() {
        String seed = "Avoid " + uniqueCode();
        String first = login(uniqueCode());
        String second = login(uniqueCode());

        List<WeightedTrack> firstRun = generate(first, seed, 3).tracks();
        create(first, seed, firstRun).expectStatus().isOk();

        // Creating the same tracks again must not fail on the unique key.
        create(first, seed, firstRun).expectStatus().isOk();

        Set<String> secondRun = uris(generate(first, seed, 3).tracks());

        assertEquals(3, secondRun.size());
        assertTrue(uris(firstRun).stream().noneMatch(secondRun::contains));
        assertEquals(uris(firstRun), uris(generate(second, seed, 3).tracks()));
    }

    @Test
    void replaceTrackReturnsATrackNotAlreadyInThePlaylist() {
        String seed = "Replace " + uniqueCode();
        String token = login(uniqueCode());
        Map<String, Object> request = generationRequest(seed, 3);
        List<WeightedTrack> tracks = generate(token, seed, 3).tracks();

        PlaylistReplaceTrackResult result = client().post().uri("/api/playlists/replace-track")
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(Map.of(
                        "generationRequest", request,
                        "currentTracks", tracks,
                        "trackToReplace", tracks.get(0)
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PlaylistReplaceTrackResult.class)
                .returnResult()
                .getResponseBody();

        assertFalse(uris(tracks).contains(result.replacementTrack().track().spotifyUri()));
    }

    @Test
    void generationUsesOneTopTracksCallPerArtistAndNoTrackLookups() {
        String seed = "Calls " + uniqueCode();
        String token = login(uniqueCode());
        int topTracksBefore = FAKE.lastFmCallCount("artist.gettoptracks");
        int trackInfoBefore = FAKE.lastFmCallCount("track.getinfo");

        int artistInfoBefore = FAKE.lastFmCallCount("artist.getinfo");

        List<WeightedTrack> tracks = generate(token, seed, 3).tracks();
        assertEquals(3, tracks.size());

        // The seed artist plus the three similar artists the fake returns.
        assertEquals(4, FAKE.lastFmCallCount("artist.gettoptracks") - topTracksBefore);
        assertEquals(trackInfoBefore, FAKE.lastFmCallCount("track.getinfo"));

        // Listener counts are looked up only for the artists that made the playlist, and still shown.
        long chosenArtists = tracks.stream().map(track -> track.track().artistName()).distinct().count();
        assertEquals(chosenArtists, FAKE.lastFmCallCount("artist.getinfo") - artistInfoBefore);
        assertTrue(tracks.stream().allMatch(track -> track.track().artistListeners() != null));
    }

    @Test
    void spotifyLookupsRunInParallelButKeepRankingOrder() {
        String seed = "Parallel " + uniqueCode();
        String token = login(uniqueCode());

        FAKE.maxConcurrentTrackSearches.set(0);

        List<WeightedTrack> tracks = generate(token, seed, 10).tracks();

        assertEquals(10, tracks.size());

        int maxConcurrent = FAKE.maxConcurrentTrackSearches.get();
        assertTrue(maxConcurrent > 1, "lookups ran one at a time");
        assertTrue(maxConcurrent <= 5, "more than 5 lookups ran at once: " + maxConcurrent);

        for (int i = 1; i < tracks.size(); i++) {
            assertTrue(tracks.get(i - 1).score() >= tracks.get(i).score(), "tracks are out of ranking order");
        }
    }

    @Test
    void failedTrackAddRemovesTheEmptyPlaylist() {
        String seed = "Orphan " + uniqueCode();
        String token = login(uniqueCode());
        List<WeightedTrack> tracks = generate(token, seed, 2).tracks();
        int removalsBefore = FAKE.libraryRemovals.size();

        FAKE.failAddingTracks = true;

        try {
            create(token, seed, tracks).expectStatus().is5xxServerError();
        } finally {
            FAKE.failAddingTracks = false;
        }

        assertEquals(removalsBefore + 1, FAKE.libraryRemovals.size());
        assertTrue(FAKE.libraryRemovals.get(removalsBefore).startsWith("spotify:playlist:"));
        assertTrue(history(token).isEmpty());
    }

    @Test
    void createValidatesItsInput() {
        String token = login(uniqueCode());

        client().post().uri("/api/playlists/create")
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(Map.of("tracks", List.of()))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Playlist name is required.");
    }

    @Test
    void rejectedLastFmKeyIsReportedInsteadOfAnEmptyPlaylist() {
        String token = login(uniqueCode());

        FAKE.rejectLastFmKey = true;

        try {
            client().post().uri("/api/playlists/generate")
                    .headers(headers -> headers.setBearerAuth(token))
                    .bodyValue(generationRequest("Rejected " + uniqueCode(), 3))
                    .exchange()
                    .expectStatus().isEqualTo(502)
                    .expectBody()
                    .jsonPath("$.message").isEqualTo("Last.fm rejected the API key. Check LASTFM_API_KEY.");
        } finally {
            FAKE.rejectLastFmKey = false;
        }
    }

    @Test
    void rateLimitIsPerAccountCarriesCorsHeadersAndIgnoresForwardedFor() {
        String limited = login(uniqueCode());
        String other = login(uniqueCode());
        Map<String, Object> invalid = generationRequest("A", 0);

        for (int i = 0; i < 5; i++) {
            client().post().uri("/api/playlists/generate")
                    .headers(headers -> headers.setBearerAuth(limited))
                    .bodyValue(invalid)
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        client().post().uri("/api/playlists/generate")
                .headers(headers -> headers.setBearerAuth(limited))
                .header("Origin", ALLOWED_ORIGIN)
                .header("X-Forwarded-For", "9.9.9.9")
                .bodyValue(invalid)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Access-Control-Allow-Origin", ALLOWED_ORIGIN);

        client().post().uri("/api/playlists/generate")
                .headers(headers -> headers.setBearerAuth(other))
                .bodyValue(invalid)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void demoEndpointSharesTheGenerateLimit() {
        String token = login(uniqueCode());
        Map<String, Object> invalid = generationRequest("A", 0);

        for (int i = 0; i < 5; i++) {
            client().post().uri("/api/playlists/generate")
                    .headers(headers -> headers.setBearerAuth(token))
                    .bodyValue(invalid)
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        client().get().uri("/api/playlists/demo")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void disconnectDeletesTheAccountsDataAndEndsAllItsSessions() {
        String code = uniqueCode();
        String seed = "Disconnect " + uniqueCode();
        String phone = login(code);
        String laptop = login(code);
        String other = login(uniqueCode());

        create(phone, seed, generate(phone, seed, 2).tracks()).expectStatus().isOk();
        create(other, seed, generate(other, seed, 2).tracks()).expectStatus().isOk();

        assertTrue(connected(laptop));
        assertEquals(2L, usedTrackCount("account-" + code));

        client().post().uri("/api/spotify/logout")
                .headers(headers -> headers.setBearerAuth(phone))
                .exchange()
                .expectStatus().isOk();

        assertFalse(connected(phone));
        assertFalse(connected(laptop));
        assertNull(sessionStore.load(laptop).block());
        assertEquals(0L, usedTrackCount("account-" + code));
        assertTrue(history(login(code)).isEmpty());
        assertEquals(1, history(other).size());
    }

    @Test
    void loginAsksOnlyForPlaylistPermissions() {
        String scope = UriComponentsBuilder.fromUri(startLogin().getResponseHeaders().getLocation())
                .build()
                .getQueryParams()
                .getFirst("scope");

        assertEquals(
                "playlist-modify-public playlist-modify-private",
                URLDecoder.decode(scope, StandardCharsets.UTF_8)
        );
    }

    private long usedTrackCount(String spotifyUserId) {
        Long count = databaseClient.sql("SELECT COUNT(*) AS \"total\" FROM user_used_tracks WHERE spotify_user_id = :id")
                .bind("id", spotifyUserId)
                .map((row, metadata) -> row.get("total", Long.class))
                .one()
                .block();

        return count == null ? 0L : count;
    }

    @Test
    void removingAccessInSpotifyDeletesTheAccountsData() {
        String code = "short" + uniqueCode();
        String token = login(code);
        String owner = "account-" + code;

        insertHistory(owner, "Kept until access is removed");
        assertEquals(1L, playlistCount(owner));
        assertEquals(1L, usedTrackCount(owner));

        FAKE.revokedRefreshTokens.add("refresh-" + code);

        client().post().uri("/api/playlists/generate")
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(generationRequest("Revoked " + uniqueCode(), 1))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.message").isEqualTo(
                        "RabbitHole's access was removed in your Spotify account, so your RabbitHole data was deleted. "
                                + "Connect Spotify to start again."
                );

        assertFalse(connected(token));
        assertEquals(0L, playlistCount(owner));
        assertEquals(0L, usedTrackCount(owner));
    }

    @Test
    void historySavedUnderTheOldUserIdMovesToTheAccountIdAtLogin() {
        String code = uniqueCode();
        String legacyOwner = "user-" + code;
        String accountOwner = "account-" + code;

        insertHistory(legacyOwner, "Saved before account ids");

        List<CreatedPlaylistHistoryItem> items = history(login(code));

        assertEquals(1, items.size());
        assertEquals("Saved before account ids", items.get(0).playlistName());
        assertEquals(0L, playlistCount(legacyOwner));
        assertEquals(0L, usedTrackCount(legacyOwner));
        assertEquals(1L, usedTrackCount(accountOwner));
    }

    private void insertHistory(String owner, String playlistName) {
        databaseClient.sql("""
                        INSERT INTO created_playlists
                            (id, spotify_user_id, playlist_name, seed_artist, track_count, spotify_playlist_url, created_at)
                        VALUES (:id, :owner, :name, 'Seed', 1, 'https://open.spotify.com/playlist/old', '2026-11-01T00:00:00Z')
                        """)
                .bind("id", UUID.randomUUID().toString())
                .bind("owner", owner)
                .bind("name", playlistName)
                .fetch()
                .rowsUpdated()
                .block();

        databaseClient.sql("""
                        INSERT INTO user_used_tracks
                            (id, spotify_user_id, playlist_id, track_name, artist_name, normalized_key, spotify_uri, created_at)
                        VALUES (:id, :owner, 'old', 'Song', 'Seed', 'seed|song', 'spotify:track:old', '2026-11-01T00:00:00Z')
                        """)
                .bind("id", UUID.randomUUID().toString())
                .bind("owner", owner)
                .fetch()
                .rowsUpdated()
                .block();
    }

    private long playlistCount(String owner) {
        Long count = databaseClient.sql("SELECT COUNT(*) AS \"total\" FROM created_playlists WHERE spotify_user_id = :id")
                .bind("id", owner)
                .map((row, metadata) -> row.get("total", Long.class))
                .one()
                .block();

        return count == null ? 0L : count;
    }

    @Test
    void sessionsSurviveARestartWithoutStoringCredentialsInTheClear() {
        String code = uniqueCode();
        String token = login(code);

        SpotifySession restored = sessionStore.load(token).block();

        assertNotNull(restored);
        assertEquals("account-" + code, restored.spotifyUserId());
        assertEquals("refresh-" + code, restored.refreshToken());

        List<Map<String, Object>> rows = databaseClient.sql("SELECT * FROM spotify_sessions")
                .fetch()
                .all()
                .collectList()
                .block();

        assertNotNull(rows);
        assertFalse(rows.isEmpty());

        String stored = rows.toString();

        assertFalse(stored.contains(token));
        assertFalse(stored.contains("refresh-" + code));
    }

    @Test
    void createReportsTheSpotifyLink() {
        String seed = "Link " + uniqueCode();
        String token = login(uniqueCode());
        List<WeightedTrack> tracks = generate(token, seed, 2).tracks();

        CreatedPlaylistResponse response = create(token, seed, tracks)
                .expectStatus().isOk()
                .expectBody(CreatedPlaylistResponse.class)
                .returnResult()
                .getResponseBody();

        assertEquals(2, response.trackCount());
        assertTrue(response.spotifyPlaylistUrl().startsWith("https://open.spotify.com/playlist/"));
    }
}
