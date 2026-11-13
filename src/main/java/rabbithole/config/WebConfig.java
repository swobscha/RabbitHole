package rabbithole.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

@Configuration
public class WebConfig implements WebFluxConfigurer {

    private final SpotifySessionArgumentResolver spotifySessionArgumentResolver;

    public WebConfig(SpotifySessionArgumentResolver spotifySessionArgumentResolver) {
        this.spotifySessionArgumentResolver = spotifySessionArgumentResolver;
    }

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        configurer.addCustomResolver(spotifySessionArgumentResolver);
    }
}
