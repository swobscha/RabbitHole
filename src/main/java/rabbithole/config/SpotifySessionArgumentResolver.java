package rabbithole.config;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import rabbithole.model.SpotifySession;
import rabbithole.service.SpotifyAuthService;

import java.util.Optional;

/**
 * Lets a controller take a {@link SpotifySession} parameter, where requests without a login get a
 * 401, or an {@code Optional<SpotifySession>} parameter for endpoints that also work logged out.
 */
@Component
public class SpotifySessionArgumentResolver implements HandlerMethodArgumentResolver {

    private final SpotifyAuthService spotifyAuthService;

    public SpotifySessionArgumentResolver(SpotifyAuthService spotifyAuthService) {
        this.spotifyAuthService = spotifyAuthService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return SpotifySession.class.equals(parameter.nestedIfOptional().getNestedParameterType());
    }

    @Override
    public Mono<Object> resolveArgument(
            MethodParameter parameter,
            BindingContext bindingContext,
            ServerWebExchange exchange
    ) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (Optional.class.equals(parameter.getParameterType())) {
            return spotifyAuthService.findSession(authorization)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .cast(Object.class);
        }

        return spotifyAuthService.requireSession(authorization).cast(Object.class);
    }
}
