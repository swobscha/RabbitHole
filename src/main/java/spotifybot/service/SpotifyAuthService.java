package spotifybot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class SpotifyAuthService {

    private static final String SPOTIFY_AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final long TOKEN_EXPIRY_BUFFER_MS = 60_000L;

    private final WebClient accountsWebClient;

    @Value("${SPOTIFY_CLIENT_ID:}")
    private String clientId;

    @Value("${SPOTIFY_CLIENT_SECRET:}")
    private String clientSecret;

    @Value("${SPOTIFY_REDIRECT_URI:http://127.0.0.1:8080/api/spotify/callback}")
    private String redirectUri;

    private volatile String accessToken = "";
    private volatile String refreshToken = "";
    private volatile long accessTokenExpiresAtMs = 0L;

    public SpotifyAuthService() {
        this.accountsWebClient = WebClient.builder()
                .baseUrl("https://accounts.spotify.com")
                .build();
    }

    public String buildAuthorizationUrl() {
        ensureSpotifyConfigPresent();

        String scope = String.join(" ",
                "playlist-modify-public",
                "playlist-modify-private",
                "user-read-private",
                "user-read-email"
        );

        return SPOTIFY_AUTHORIZE_URL
                + "?client_id=" + encode(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(scope)
                + "&show_dialog=true";
    }

    public Mono<Void> handleCallback(String code) {
        ensureSpotifyConfigPresent();

        if (code == null || code.isBlank()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Spotify callback did not include an authorization code."
            ));
        }

        return accountsWebClient.post()
                .uri("/api/token")
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters
                        .fromFormData("grant_type", "authorization_code")
                        .with("code", code)
                        .with("redirect_uri", redirectUri))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnNext(this::saveTokenResponse)
                .then();
    }

    public Mono<String> getValidAccessToken() {
        if (hasUsableAccessToken()) {
            return Mono.just(accessToken);
        }

        if (refreshToken != null && !refreshToken.isBlank()) {
            return refreshAccessToken();
        }

        return Mono.error(new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Spotify is not connected. Connect Spotify and try again."
        ));
    }

    public boolean isConnected() {
        return hasUsableAccessToken() || (refreshToken != null && !refreshToken.isBlank());
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

    public void clearTokens() {
        accessToken = "";
        refreshToken = "";
        accessTokenExpiresAtMs = 0L;
    }

    private Mono<String> refreshAccessToken() {
        ensureSpotifyConfigPresent();

        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Spotify is not connected. Connect Spotify and try again."
            ));
        }

        return accountsWebClient.post()
                .uri("/api/token")
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters
                        .fromFormData("grant_type", "refresh_token")
                        .with("refresh_token", refreshToken))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    saveTokenResponse(json);
                    return accessToken;
                });
    }

    private boolean hasUsableAccessToken() {
        return accessToken != null
                && !accessToken.isBlank()
                && System.currentTimeMillis() < accessTokenExpiresAtMs;
    }

    private void saveTokenResponse(JsonNode json) {
        String newAccessToken = json.path("access_token").asText("");
        String newRefreshToken = json.path("refresh_token").asText("");
        long expiresInSeconds = json.path("expires_in").asLong(3600L);

        if (newAccessToken == null || newAccessToken.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Spotify did not return an access token."
            );
        }

        accessToken = newAccessToken;

        if (newRefreshToken != null && !newRefreshToken.isBlank()) {
            refreshToken = newRefreshToken;
        }

        long safeExpiresInMs = Math.max(60L, expiresInSeconds) * 1000L;
        accessTokenExpiresAtMs = System.currentTimeMillis()
                + safeExpiresInMs
                - TOKEN_EXPIRY_BUFFER_MS;

        System.out.println(
                "Spotify tokens saved"
                        + " | expiresInSeconds=" + expiresInSeconds
                        + " | hasRefreshToken=" + (refreshToken != null && !refreshToken.isBlank())
                        + " | redirectUri=" + redirectUri
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