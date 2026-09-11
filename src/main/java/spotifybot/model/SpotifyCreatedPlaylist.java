package spotifybot.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record SpotifyCreatedPlaylist(
        String id,
        String name,

        @JsonProperty("external_urls")
        Map<String, String> externalUrls
) {
}