package rabbithole.model;

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
}
