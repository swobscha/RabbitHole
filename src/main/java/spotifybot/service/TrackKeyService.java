package spotifybot.service;

import org.springframework.stereotype.Service;
import spotifybot.model.TrackCandidate;

import java.util.Locale;

@Service
public class TrackKeyService {

    public String normalizedKey(TrackCandidate track) {
        return normalizedKey(track.artistName(), track.name());
    }

    public String normalizedKey(String artistName, String trackName) {
        String artist = clean(artistName);
        String title = clean(trackName);

        if (artist.isBlank() || title.isBlank()) {
            return "";
        }

        return artist + "|" + title;
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }

        String withoutExtras = value.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\[[^]]*]", "")
                .replaceAll("\\bfeat\\..*", "")
                .replaceAll("\\bft\\..*", "");

        String cleaned = TrackText.simplify(withoutExtras);

        return cleaned.isBlank() ? TrackText.simplify(value) : cleaned;
    }
}
