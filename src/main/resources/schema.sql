CREATE TABLE IF NOT EXISTS created_playlists (
                                                 id VARCHAR(100) PRIMARY KEY,
    playlist_name VARCHAR(255) NOT NULL,
    seed_artist VARCHAR(255),
    track_count INT NOT NULL,
    spotify_playlist_url VARCHAR(1000),
    created_at VARCHAR(100) NOT NULL
    );

CREATE TABLE IF NOT EXISTS used_tracks (
                                           id VARCHAR(100) PRIMARY KEY,
    playlist_id VARCHAR(100),
    track_name VARCHAR(500) NOT NULL,
    artist_name VARCHAR(500) NOT NULL,
    normalized_key VARCHAR(1000) NOT NULL UNIQUE,
    spotify_uri VARCHAR(1000),
    created_at VARCHAR(100) NOT NULL
    );