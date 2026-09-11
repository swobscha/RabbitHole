package spotifybot.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import spotifybot.model.UsedTrackEntity;

public interface UsedTrackRepository extends ReactiveCrudRepository<UsedTrackEntity, String> {

    @Query("SELECT normalized_key FROM used_tracks")
    Flux<String> findAllNormalizedKeys();
}