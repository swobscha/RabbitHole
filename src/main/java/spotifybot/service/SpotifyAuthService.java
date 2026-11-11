package spotifybot.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import spotifybot.model.SpotifySession;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

@Service
public class SpotifyAuthService {

    private static final Logger log = LoggerFactory.getLogger(SpotifyAuthService.class);

    private static final long TOKEN_EXPIRY_BUFFER_MS = 60_000L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SESSION_IDLE_LIMIT = Duration.ofDays(30);

    private static final int RANDOM_TOKEN_BYTES = 32;
    private static final Pattern SESSION_TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final String BEARER_PREFIX = "Bearer ";

    private final SpotifySessionStore sessionStore;
    private final PlaylistHistoryService playlistHistoryService;
    private final WebClient accountsWebClient;
    private final WebClient apiWebClient;
    private final String authorizeUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    private final Cache<String, SpotifySession> activeSessions = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(6))
            .build();

    @Value("${SPOTIFY_CLIENT_ID:}")
    private String clientId;

    @Value("${SPOTIFY_CLIENT_SECRET:}")
    private String clientSecret;

    @Value("${SPOTIFY_REDIRECT_URI:http://127.0.0.1:8080/api/spotify/callback}")
    private String redirectUri;

    public SpotifyAuthService(
            SpotifySessionStore sessionStore,
            PlaylistHistoryService playlistHistoryService,
            @Value("${SPOTIFY_ACCOUNTS_URL:https://accounts.spotify.com}") String accountsUrl,
            @Value("${SPOTIFY_API_URL:https://api.spotify.com/v1}") String apiUrl
    ) {
        this.sessionStore = sessionStore;
        this.playlistHistoryService = playlistHistoryService;
        this.accountsWebClient = WebClient.builder().baseUrl(accountsUrl).build();
        this.apiWebClient = WebClient.builder().baseUrl(apiUrl).build();
        this.authorizeUrl = accountsUrl + "/authorize";
    }

    public String newLoginState() {
        return randomToken();
    }

    public String buildAuthorizationUrl(String state) {
        ensureSpotifyConfigPresent();

        // Only what the app uses: creating playlists. The account id from /me needs no scope.
        String scope = String.join(" ",
                "playlist-modify-public",
                "playlist-modify-private"
        );

        return authorizeUrl
                + "?client_id=" + encode(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(scope)
                + "&state=" + encode(state)
                + "&show_dialog=true";
    }

    /** Exchanges an authorization code for a new session and returns its bearer token. */
    public Mono<String> completeLogin(String code) {
        return Mono.defer(() -> {
            ensureSpotifyConfigPresent();

            return requestTokens(BodyInserters
                    .fromFormData("grant_type", "authorization_code")
                    .with("code", code)
                    .with("redirect_uri", redirectUri))
                    .flatMap(tokens -> fetchSpotifyAccount(requireAccessToken(tokens))
                            .flatMap(account -> moveLegacyData(account)
                                    .then(createSession(account.accountId(), tokens))));
        });
    }

    /** Finds the session for an Authorization header, or completes empty if there is none. */
    public Mono<SpotifySession> findSession(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);

        if (token.isEmpty()) {
            return Mono.empty();
        }

        String lookupId = SpotifySessionStore.lookupId(token);
        SpotifySession cached = activeSessions.getIfPresent(lookupId);

        if (cached != null) {
            return Mono.just(cached);
        }

        return sessionStore.load(token)
                .doOnNext(session -> activeSessions.put(lookupId, session));
    }

    public Mono<SpotifySession> requireSession(String authorizationHeader) {
        return findSession(authorizationHeader)
                .switchIfEmpty(Mono.error(this::notConnected));
    }

    public Mono<String> getValidAccessToken(SpotifySession session) {
        if (session.hasUsableAccessToken()) {
            return Mono.just(session.accessToken());
        }

        return session.refreshOnce(() -> refreshAccessToken(session));
    }

    /**
     * Deletes the account's history and ends every session it has, on all devices, as Spotify's
     * developer policy requires on disconnect. History goes first so that a failure leaves the
     * user connected and able to retry.
     */
    public Mono<Void> deleteAccount(SpotifySession session) {
        String spotifyUserId = session.spotifyUserId();

        return playlistHistoryService.clearHistory(spotifyUserId)
                .then(Mono.defer(() -> {
                    forgetCachedSessions(spotifyUserId);
                    return sessionStore.deleteAllForUser(spotifyUserId);
                }));
    }

    private void forgetCachedSessions(String spotifyUserId) {
        activeSessions.asMap().values().removeIf(cached -> cached.spotifyUserId().equals(spotifyUserId));
    }

    /**
     * Accounts used to be keyed by Spotify's user id, which can change. Spotify now recommends
     * account_id, so data saved under the old id is moved to the account_id at login.
     */
    private Mono<Void> moveLegacyData(SpotifyAccount account) {
        String legacyUserId = account.legacyUserId();

        if (legacyUserId.isBlank() || legacyUserId.equals(account.accountId())) {
            return Mono.empty();
        }

        return Mono.defer(() -> {
                    forgetCachedSessions(legacyUserId);

                    return playlistHistoryService.moveToAccount(legacyUserId, account.accountId())
                            .then(sessionStore.moveToAccount(legacyUserId, account.accountId()));
                })
                .onErrorResume(error -> {
                    log.error("Could not move data saved under a Spotify user id to its account id.", error);
                    return Mono.empty();
                });
    }

    public boolean isConfigured() {
        return clientId != null
                && !clientId.isBlank()
                && clientSecret != null
                && !clientSecret.isBlank()
                && redirectUri != null
                && !redirectUri.isBlank();
    }

    public String configuredRedirectUri() {
        if (redirectUri == null) {
            return "";
        }

        return redirectUri.trim();
    }

    private Mono<String> createSession(String spotifyUserId, JsonNode tokens) {
        String refreshToken = tokens.path("refresh_token").asText("");

        if (refreshToken.isBlank()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Spotify did not return a refresh token."
            ));
        }

        String token = randomToken();
        SpotifySession session = new SpotifySession(token, spotifyUserId, refreshToken);

        applyTokenResponse(session, tokens);
        activeSessions.put(SpotifySessionStore.lookupId(token), session);

        long idleCutoffMs = System.currentTimeMillis() - SESSION_IDLE_LIMIT.toMillis();

        return sessionStore.deleteIdleSince(idleCutoffMs)
                .then(sessionStore.save(session))
                .onErrorResume(error -> {
                    log.error("Could not persist a Spotify session; it will last until the next restart.", error);
                    return Mono.empty();
                })
                .thenReturn(token);
    }

    private Mono<String> refreshAccessToken(SpotifySession session) {
        return Mono.defer(() -> {
            ensureSpotifyConfigPresent();

            return requestTokens(BodyInserters
                    .fromFormData("grant_type", "refresh_token")
                    .with("refresh_token", session.refreshToken()));
        })
                .flatMap(tokens -> {
                    String accessToken = applyTokenResponse(session, tokens);

                    return sessionStore.recordRefresh(session)
                            .onErrorResume(error -> {
                                log.error("Could not persist a refreshed Spotify session.", error);
                                return Mono.empty();
                            })
                            .thenReturn(accessToken);
                })
                .onErrorResume(WebClientResponseException.class, error -> {
                    log.warn("Spotify token refresh failed with status {}.", error.getStatusCode().value());

                    // invalid_grant means the user removed this app's access in their Spotify account,
                    // which Spotify's developer policy treats as a request to delete their data.
                    if (error.getStatusCode().value() == 400 && error.getResponseBodyAsString().contains("invalid_grant")) {
                        return deleteAccount(session).then(Mono.<String>error(accessRemoved()));
                    }

                    if (error.getStatusCode().value() == 400) {
                        return endSession(session).then(Mono.<String>error(connectionExpired()));
                    }

                    return Mono.error(new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "Spotify is temporarily unavailable. Try again in a moment."
                    ));
                });
    }

    private Mono<Void> endSession(SpotifySession session) {
        activeSessions.invalidate(SpotifySessionStore.lookupId(session.token()));

        return sessionStore.delete(session.token());
    }

    private Mono<JsonNode> requestTokens(BodyInserters.FormInserter<String> form) {
        return accountsWebClient.post()
                .uri("/api/token")
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(REQUEST_TIMEOUT);
    }

    private Mono<SpotifyAccount> fetchSpotifyAccount(String accessToken) {
        return apiWebClient.get()
                .uri("/me")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(REQUEST_TIMEOUT)
                .map(json -> {
                    String userId = json.path("id").asText("");
                    String accountId = json.path("account_id").asText("");

                    return new SpotifyAccount(accountId.isBlank() ? userId : accountId, userId);
                })
                .filter(account -> !account.accountId().isBlank())
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Spotify did not return an account id."
                )))
                .onErrorMap(WebClientResponseException.Forbidden.class, error -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "This Spotify account is not allowed to use this app yet. "
                                + "The app owner can add it in the Spotify developer dashboard."
                ));
    }

    private String requireAccessToken(JsonNode tokens) {
        String accessToken = tokens.path("access_token").asText("");

        if (accessToken.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Spotify did not return an access token."
            );
        }

        return accessToken;
    }

    private String applyTokenResponse(SpotifySession session, JsonNode tokens) {
        String accessToken = requireAccessToken(tokens);
        long expiresInSeconds = tokens.path("expires_in").asLong(3600L);
        long safeExpiresInMs = Math.max(60L, expiresInSeconds) * 1000L;

        session.updateTokens(
                accessToken,
                tokens.path("refresh_token").asText(""),
                System.currentTimeMillis() + safeExpiresInMs - TOKEN_EXPIRY_BUFFER_MS
        );

        return accessToken;
    }

    private String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return "";
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();

        return SESSION_TOKEN_FORMAT.matcher(token).matches() ? token : "";
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseStatusException notConnected() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Spotify is not connected. Connect Spotify and try again."
        );
    }

    private ResponseStatusException accessRemoved() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "MusicFinder's access was removed in your Spotify account, so your MusicFinder data was deleted. "
                        + "Connect Spotify to start again."
        );
    }

    /** accountId is Spotify's stable account_id; legacyUserId is the older id it replaces. */
    private record SpotifyAccount(String accountId, String legacyUserId) {
    }

    private ResponseStatusException connectionExpired() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Your Spotify connection has expired. Connect Spotify and try again."
        );
    }

    private void ensureSpotifyConfigPresent() {
        if (!isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Spotify is not configured. Check SPOTIFY_CLIENT_ID, SPOTIFY_CLIENT_SECRET, and SPOTIFY_REDIRECT_URI."
            );
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
