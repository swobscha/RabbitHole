package spotifybot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;
import spotifybot.model.PlaylistGenerationRequest;
import spotifybot.model.TrackCandidate;
import spotifybot.model.WeightedTrack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class RankingDebugService {

    private static final Logger log = LoggerFactory.getLogger(RankingDebugService.class);

    private static final int MAX_ROWS = 5000;

    private static final Path DEBUG_FOLDER = Path.of("debug-rankings");
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final boolean enabled;

    public RankingDebugService(@Value("${DEBUG_RANKINGS_ENABLED:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public void writeCandidateRankingDebug(
            PlaylistGenerationRequest request,
            int round,
            List<WeightedTrack> rankedTracks
    ) {
        if (!enabled) {
            return;
        }

        List<WeightedTrack> snapshot = List.copyOf(rankedTracks);

        Schedulers.boundedElastic().schedule(() -> writeCandidateCsv(request, round, snapshot));
    }

    private void writeCandidateCsv(
            PlaylistGenerationRequest request,
            int round,
            List<WeightedTrack> rankedTracks
    ) {
        try {
            Files.createDirectories(DEBUG_FOLDER);

            String fileName = "ranking-"
                    + safeFileName(request.seedArtist())
                    + "-round-"
                    + round
                    + "-"
                    + LocalDateTime.now().format(FILE_TIME_FORMAT)
                    + ".csv";

            Path filePath = DEBUG_FOLDER.resolve(fileName);

            StringBuilder csv = new StringBuilder();

            csv.append("rank,")
                    .append("score,")
                    .append("artist,")
                    .append("track,")
                    .append("album,")
                    .append("sourceApi,")
                    .append("spotifyUri,")
                    .append("popularity,")
                    .append("artistListeners,")
                    .append("artistPlaycount,")
                    .append("trackListeners,")
                    .append("trackPlaycount,")
                    .append("artistSimilarity,")
                    .append("genres,")
                    .append("reasons")
                    .append("\n");

            int rows = Math.min(rankedTracks.size(), MAX_ROWS);

            for (int index = 0; index < rows; index++) {
                WeightedTrack weightedTrack = rankedTracks.get(index);
                TrackCandidate track = weightedTrack.track();

                csv.append(index + 1).append(",")
                        .append(weightedTrack.score()).append(",")
                        .append(csv(track.artistName())).append(",")
                        .append(csv(track.name())).append(",")
                        .append(csv(track.albumName())).append(",")
                        .append(csv(track.sourceApi())).append(",")
                        .append(csv(track.spotifyUri())).append(",")
                        .append(csv(track.popularity())).append(",")
                        .append(csv(track.artistListeners())).append(",")
                        .append(csv(track.artistPlaycount())).append(",")
                        .append(csv(track.trackListeners())).append(",")
                        .append(csv(track.trackPlaycount())).append(",")
                        .append(csv(track.artistSimilarity())).append(",")
                        .append(csv(track.genres())).append(",")
                        .append(csv(weightedTrack.reasons()))
                        .append("\n");
            }

            Files.writeString(filePath, csv.toString(), StandardCharsets.UTF_8);

            log.info("Ranking debug CSV written: {}", filePath.toAbsolutePath());
        } catch (IOException error) {
            log.warn("Could not write ranking debug CSV: {}", error.getMessage());
        }
    }

    public void writeSelectedRankingDebug(
            PlaylistGenerationRequest request,
            int round,
            List<WeightedTrack> selectedTracks
    ) {
        if (!enabled) {
            return;
        }

        List<WeightedTrack> snapshot = List.copyOf(selectedTracks);

        Schedulers.boundedElastic().schedule(() -> writeSelectedCsv(request, round, snapshot));
    }

    private void writeSelectedCsv(
            PlaylistGenerationRequest request,
            int round,
            List<WeightedTrack> selectedTracks
    ) {
        try {
            Files.createDirectories(DEBUG_FOLDER);

            String fileName = "selected-"
                    + safeFileName(request.seedArtist())
                    + "-round-"
                    + round
                    + "-"
                    + LocalDateTime.now().format(FILE_TIME_FORMAT)
                    + ".csv";

            Path filePath = DEBUG_FOLDER.resolve(fileName);

            StringBuilder csv = new StringBuilder();

            csv.append("rank,")
                    .append("score,")
                    .append("artist,")
                    .append("track,")
                    .append("spotifyUri,")
                    .append("artistListeners,")
                    .append("artistPlaycount,")
                    .append("trackListeners,")
                    .append("trackPlaycount,")
                    .append("artistSimilarity,")
                    .append("genres,")
                    .append("reasons")
                    .append("\n");

            for (int index = 0; index < selectedTracks.size(); index++) {
                WeightedTrack weightedTrack = selectedTracks.get(index);
                TrackCandidate track = weightedTrack.track();

                csv.append(index + 1).append(",")
                        .append(weightedTrack.score()).append(",")
                        .append(csv(track.artistName())).append(",")
                        .append(csv(track.name())).append(",")
                        .append(csv(track.spotifyUri())).append(",")
                        .append(csv(track.artistListeners())).append(",")
                        .append(csv(track.artistPlaycount())).append(",")
                        .append(csv(track.trackListeners())).append(",")
                        .append(csv(track.trackPlaycount())).append(",")
                        .append(csv(track.artistSimilarity())).append(",")
                        .append(csv(track.genres())).append(",")
                        .append(csv(weightedTrack.reasons()))
                        .append("\n");
            }

            Files.writeString(filePath, csv.toString(), StandardCharsets.UTF_8);

            log.info("Selected debug CSV written: {}", filePath.toAbsolutePath());
        } catch (IOException error) {
            log.warn("Could not write selected debug CSV: {}", error.getMessage());
        }
    }

    private String csv(Object value) {
        if (value == null) {
            return "\"\"";
        }

        String text = value.toString()
                .replace("\"", "\"\"")
                .replace("\n", " ")
                .replace("\r", " ");

        return "\"" + text + "\"";
    }

    private String safeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }

        return value.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
    }
}