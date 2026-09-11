package spotifybot.model;

import java.util.Set;

public record TrackCandidate(
        String id,
        String name,
        String artistName,
        String artistId,
        String albumName,
        int popularity,
        Set<String> genres,
        String sourceApi,
        String spotifyUri,
        Long artistListeners,
        Long artistPlaycount,
        Long trackListeners,
        Long trackPlaycount,
        Double artistSimilarity
) {
    public TrackCandidate(
            String id,
            String name,
            String artistName,
            String artistId,
            String albumName,
            int popularity,
            Set<String> genres,
            String sourceApi,
            String spotifyUri
    ) {
        this(
                id,
                name,
                artistName,
                artistId,
                albumName,
                popularity,
                genres,
                sourceApi,
                spotifyUri,
                null,
                null,
                null,
                null,
                null
        );
    }
}