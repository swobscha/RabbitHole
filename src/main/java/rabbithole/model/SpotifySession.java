package rabbithole.model;

import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/**
 * One connected Spotify account. Controllers receive this as a method argument, resolved from
 * the request's bearer token, so every request acts only on its own account.
 */
public final class SpotifySession {

    private final String token;

    /** Spotify's stable account_id, which keys this account's data (stored as spotify_user_id). */
    private final String spotifyUserId;

    private volatile String accessToken = "";
    private volatile String refreshToken;
    private volatile long accessTokenExpiresAtMs = 0L;

    private Mono<String> refreshInFlight;

    public SpotifySession(String token, String spotifyUserId, String refreshToken) {
        this.token = token;
        this.spotifyUserId = spotifyUserId;
        this.refreshToken = refreshToken;
    }

    public String token() {
        return token;
    }

    public String spotifyUserId() {
        return spotifyUserId;
    }

    public String accessToken() {
        return accessToken;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public boolean hasUsableAccessToken() {
        return !accessToken.isBlank() && System.currentTimeMillis() < accessTokenExpiresAtMs;
    }

    public void updateTokens(String newAccessToken, String newRefreshToken, long expiresAtMs) {
        accessToken = newAccessToken;
        accessTokenExpiresAtMs = expiresAtMs;

        if (newRefreshToken != null && !newRefreshToken.isBlank()) {
            refreshToken = newRefreshToken;
        }
    }

    /** Shares one token refresh between concurrent requests on this session. */
    public synchronized Mono<String> refreshOnce(Supplier<Mono<String>> refresher) {
        if (refreshInFlight == null) {
            refreshInFlight = refresher.get()
                    .doFinally(ignored -> clearRefreshInFlight())
                    .cache();
        }

        return refreshInFlight;
    }

    private synchronized void clearRefreshInFlight() {
        refreshInFlight = null;
    }
}
