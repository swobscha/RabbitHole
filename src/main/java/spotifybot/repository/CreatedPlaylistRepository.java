package spotifybot.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import spotifybot.model.CreatedPlaylistEntity;

public interface CreatedPlaylistRepository extends ReactiveCrudRepository<CreatedPlaylistEntity, String> {

    @Query("SELECT * FROM created_playlists ORDER BY created_at DESC")
    Flux<CreatedPlaylistEntity> findAllNewestFirst();
}