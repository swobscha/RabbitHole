package spotifybot.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import spotifybot.service.SpotifyAuthService;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/spotify")
public class SpotifyAuthController {

    private final SpotifyAuthService spotifyAuthService;

    @Value("${FRONTEND_URL:http://localhost:5173}")
    private String frontendUrl;

    public SpotifyAuthController(SpotifyAuthService spotifyAuthService) {
        this.spotifyAuthService = spotifyAuthService;
    }

    @GetMapping("/login")
    public Mono<ResponseEntity<Void>> login() {
        String authorizationUrl = spotifyAuthService.buildAuthorizationUrl();

        return Mono.just(ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(authorizationUrl))
                .build());
    }

    @GetMapping("/callback")
    public Mono<ResponseEntity<Void>> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error
    ) {
        if (error != null && !error.isBlank()) {
            return Mono.just(redirectToFrontend(
                    "spotify=error&message=" + encode("Spotify authorization was cancelled or failed.")
            ));
        }

        if (code == null || code.isBlank()) {
            return Mono.just(redirectToFrontend(
                    "spotify=error&message=" + encode("Spotify did not return an authorization code.")
            ));
        }

        return spotifyAuthService.handleCallback(code)
                .thenReturn(redirectToFrontend("spotify=connected"))
                .onErrorReturn(redirectToFrontend(
                        "spotify=error&message=" + encode("Spotify connection failed. Check the backend console.")
                ));
    }

    @GetMapping("/status")
    public Mono<Map<String, Object>> status() {
        return Mono.just(Map.of(
                "connected", spotifyAuthService.isConnected(),
                "configured", spotifyAuthService.isConfigured(),
                "redirectUri", spotifyAuthService.configuredRedirectUri()
        ));
    }

    @GetMapping("/logout")
    public Mono<Map<String, Object>> logout() {
        spotifyAuthService.clearTokens();

        return Mono.just(Map.of(
                "connected", false,
                "status", "Spotify tokens cleared"
        ));
    }

    private ResponseEntity<Void> redirectToFrontend(String queryString) {
        String cleanFrontendUrl = frontendUrl == null || frontendUrl.isBlank()
                ? "http://localhost:5173"
                : frontendUrl.trim().replaceAll("/+$", "");

        String separator = queryString == null || queryString.isBlank()
                ? ""
                : "?" + queryString;

        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(cleanFrontendUrl + separator))
                .build();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}