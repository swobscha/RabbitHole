package spotifybot.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("used_tracks")
public class UsedTrackEntity {

    @Id
    private String id;

    @Column("playlist_id")
    private String playlistId;

    @Column("track_name")
    private String trackName;

    @Column("artist_name")
    private String artistName;

    @Column("normalized_key")
    private String normalizedKey;

    @Column("spotify_uri")
    private String spotifyUri;

    @Column("created_at")
    private String createdAt;

    public UsedTrackEntity() {
    }

    public UsedTrackEntity(
            String id,
            String playlistId,
            String trackName,
            String artistName,
            String normalizedKey,
            String spotifyUri,
            String createdAt
    ) {
        this.id = id;
        this.playlistId = playlistId;
        this.trackName = trackName;
        this.artistName = artistName;
        this.normalizedKey = normalizedKey;
        this.spotifyUri = spotifyUri;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getPlaylistId() {
        return playlistId;
    }

    public String getTrackName() {
        return trackName;
    }

    public String getArtistName() {
        return artistName;
    }

    public String getNormalizedKey() {
        return normalizedKey;
    }

    public String getSpotifyUri() {
        return spotifyUri;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}