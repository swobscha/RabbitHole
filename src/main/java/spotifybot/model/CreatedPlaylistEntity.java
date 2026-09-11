package spotifybot.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("created_playlists")
public class CreatedPlaylistEntity {

    @Id
    private String id;

    @Column("playlist_name")
    private String playlistName;

    @Column("seed_artist")
    private String seedArtist;

    @Column("track_count")
    private int trackCount;

    @Column("spotify_playlist_url")
    private String spotifyPlaylistUrl;

    @Column("created_at")
    private String createdAt;

    public CreatedPlaylistEntity() {
    }

    public CreatedPlaylistEntity(
            String id,
            String playlistName,
            String seedArtist,
            int trackCount,
            String spotifyPlaylistUrl,
            String createdAt
    ) {
        this.id = id;
        this.playlistName = playlistName;
        this.seedArtist = seedArtist;
        this.trackCount = trackCount;
        this.spotifyPlaylistUrl = spotifyPlaylistUrl;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getPlaylistName() {
        return playlistName;
    }

    public String getSeedArtist() {
        return seedArtist;
    }

    public int getTrackCount() {
        return trackCount;
    }

    public String getSpotifyPlaylistUrl() {
        return spotifyPlaylistUrl;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}