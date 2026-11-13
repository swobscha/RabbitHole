package rabbithole.service;

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
import tools.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * An app-only Spotify token (client credentials flow) for searching the catalog when nobody is
 * logged in. It can't read or change any user's data, and it doesn't count toward Spotify's
 * development-mode limit on logged-in users.
 */
@Service
public class SpotifyAppTokenService {

    private static final Logger log = LoggerFactory.getLogger(SpotifyAppTokenService.class);

    private static final long EXPIRY_BUFFER_MS = 60_000L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final WebClient accountsWebClient;

    @Value("${SPOTIFY_CLIENT_ID:}")
    private String clientId;

    @Value("${SPOTIFY_CLIENT_SECRET:}")
    private String clientSecret;

    private volatile String token = "";
    private volatile long expiresAtMs = 0L;
    private Mono<String> requestInFlight;

    public SpotifyAppTokenService(
            @Value("${SPOTIFY_ACCOUNTS_URL:https://accounts.spotify.com}") String accountsUrl
    ) {
        this.accountsWebClient = WebClient.builder().baseUrl(accountsUrl).build();
    }

    public Mono<String> getToken() {
        if (!token.isBlank() && System.currentTimeMillis() < expiresAtMs) {
            return Mono.just(token);
        }

        return requestOnce();
    }

    /** Shares one token request between concurrent callers. */
    private synchronized Mono<String> requestOnce() {
        if (requestInFlight == null) {
            requestInFlight = requestToken()
                    .doFinally(ignored -> clearRequestInFlight())
                    .cache();
        }

        return requestInFlight;
    }

    private synchronized void clearRequestInFlight() {
        requestInFlight = null;
    }

    private Mono<String> requestToken() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Spotify is not configured. Check SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET."
            ));
        }

        return accountsWebClient.post()
                .uri("/api/token")
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials"))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(REQUEST_TIMEOUT)
                .map(json -> {
                    String accessToken = json.path("access_token").asText("");

                    if (accessToken.isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Spotify did not return an access token."
                        );
                    }

                    long expiresInMs = Math.max(60L, json.path("expires_in").asLong(3600L)) * 1000L;

                    token = accessToken;
                    expiresAtMs = System.currentTimeMillis() + expiresInMs - EXPIRY_BUFFER_MS;

                    return accessToken;
                })
                .onErrorMap(WebClientResponseException.class, error -> {
                    log.error("Spotify app token request failed with status {}.", error.getStatusCode().value());

                    return new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "Spotify is temporarily unavailable. Try again in a moment."
                    );
                });
    }
}
