package rabbithole.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import rabbithole.service.SpotifyAuthService;
import rabbithole.service.SpotifyRateLimitGuard;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/spotify")
public class SpotifyAuthController {

    private static final Logger log = LoggerFactory.getLogger(SpotifyAuthController.class);

    private static final String LOGIN_STATE_COOKIE = "spotify_oauth_state";
    private static final Duration LOGIN_STATE_LIFETIME = Duration.ofMinutes(10);

    private final SpotifyAuthService spotifyAuthService;
    private final SpotifyRateLimitGuard spotifyRateLimitGuard;

    @Value("${FRONTEND_URL:http://localhost:5173}")
    private String frontendUrl;

    public SpotifyAuthController(SpotifyAuthService spotifyAuthService, SpotifyRateLimitGuard spotifyRateLimitGuard) {
        this.spotifyAuthService = spotifyAuthService;
        this.spotifyRateLimitGuard = spotifyRateLimitGuard;
    }

    @GetMapping("/login")
    public Mono<ResponseEntity<Void>> login(ServerHttpRequest request) {
        URI loopbackLogin = loopbackLoginRedirect(request);

        if (loopbackLogin != null) {
            return Mono.just(ResponseEntity.status(HttpStatus.FOUND).location(loopbackLogin).build());
        }

        String state = spotifyAuthService.newLoginState();
        String authorizationUrl = spotifyAuthService.buildAuthorizationUrl(state);

        return Mono.just(ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(authorizationUrl))
                .header(HttpHeaders.SET_COOKIE, loginStateCookie(state, LOGIN_STATE_LIFETIME).toString())
                .build());
    }

    @GetMapping("/callback")
    public Mono<ResponseEntity<Void>> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String state,
            @CookieValue(name = LOGIN_STATE_COOKIE, required = false) String expectedState
    ) {
        if (!statesMatch(state, expectedState)) {
            log.warn("Rejected a Spotify callback whose state did not match this browser's login.");

            return Mono.just(errorRedirect("Your Spotify login could not be verified. Please connect again."));
        }

        if (error != null && !error.isBlank()) {
            return Mono.just(errorRedirect("Spotify authorization was cancelled or failed."));
        }

        if (code == null || code.isBlank()) {
            return Mono.just(errorRedirect("Spotify did not return an authorization code."));
        }

        return spotifyAuthService.completeLogin(code)
                .map(sessionToken -> redirectToFrontend("#session=" + sessionToken))
                .onErrorResume(failure -> {
                    log.error("Spotify login failed.", failure);

                    return Mono.just(errorRedirect(loginFailureMessage(failure)));
                });
    }

    @GetMapping("/status")
    public Mono<Map<String, Object>> status(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        return spotifyAuthService.findSession(authorization)
                .hasElement()
                .map(connected -> Map.of(
                        "connected", connected,
                        "configured", spotifyAuthService.isConfigured(),
                        "redirectUri", spotifyAuthService.configuredRedirectUri(),
                        // When Spotify's requested pause ends (epoch ms), or 0, so the page can warn visitors up front.
                        "spotifyPausedUntil", spotifyRateLimitGuard.pausedUntilMs()
                ));
    }

    /** Disconnects the account and deletes its data, as Spotify's developer policy requires. */
    @PostMapping("/logout")
    public Mono<Map<String, Object>> logout(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        return spotifyAuthService.findSession(authorization)
                .flatMap(spotifyAuthService::deleteAccount)
                .thenReturn(Map.of(
                        "connected", false,
                        "status", "Spotify disconnected and your RabbitHole data was deleted"
                ));
    }

    /**
     * Spotify only accepts 127.0.0.1 redirect URIs for local development, so a login started on
     * localhost has to move to 127.0.0.1 first or the state cookie would not reach the callback.
     */
    private URI loopbackLoginRedirect(ServerHttpRequest request) {
        URI redirectUri = URI.create(spotifyAuthService.configuredRedirectUri());

        if ("localhost".equalsIgnoreCase(request.getURI().getHost()) && "127.0.0.1".equals(redirectUri.getHost())) {
            return UriComponentsBuilder.fromUri(request.getURI()).host("127.0.0.1").build(true).toUri();
        }

        return null;
    }

    private ResponseCookie loginStateCookie(String value, Duration maxAge) {
        return ResponseCookie.from(LOGIN_STATE_COOKIE, value)
                .httpOnly(true)
                .secure(spotifyAuthService.configuredRedirectUri().startsWith("https://"))
                .sameSite("Lax")
                .path("/api/spotify")
                .maxAge(maxAge)
                .build();
    }

    private boolean statesMatch(String state, String expectedState) {
        if (state == null || state.isBlank() || expectedState == null || expectedState.isBlank()) {
            return false;
        }

        return MessageDigest.isEqual(
                state.getBytes(StandardCharsets.UTF_8),
                expectedState.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String loginFailureMessage(Throwable failure) {
        if (failure instanceof ResponseStatusException statusException && statusException.getReason() != null) {
            return statusException.getReason();
        }

        return "Spotify connection failed. Please try again.";
    }

    private ResponseEntity<Void> errorRedirect(String message) {
        return redirectToFrontend("?spotify=error&message=" + encode(message));
    }

    private ResponseEntity<Void> redirectToFrontend(String suffix) {
        String cleanFrontendUrl = frontendUrl == null || frontendUrl.isBlank()
                ? "http://localhost:5173"
                : frontendUrl.trim().replaceAll("/+$", "");

        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(cleanFrontendUrl + suffix))
                .header(HttpHeaders.SET_COOKIE, loginStateCookie("", Duration.ZERO).toString())
                .build();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
