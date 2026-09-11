package spotifybot.service;

import org.springframework.stereotype.Service;
import spotifybot.model.TrackCandidate;

@Service
public class TrackKeyService {

    public String normalizedKey(TrackCandidate track) {
        return normalizedKey(track.artistName(), track.name());
    }

    public String normalizedKey(String artistName, String trackName) {
        return clean(artistName) + "|" + clean(trackName);
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }

        return value.toLowerCase()
                .replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\[[^]]*]", "")
                .replaceAll("feat\\..*", "")
                .replaceAll("ft\\..*", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}