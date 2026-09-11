package spotifybot.provider;

import reactor.core.publisher.Flux;
import spotifybot.model.PlaylistGenerationRequest;
import spotifybot.model.TrackCandidate;

public interface MusicApiProvider {

    Flux<TrackCandidate> findCandidateTracks(PlaylistGenerationRequest request);
}