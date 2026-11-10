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

ALTER TABLE created_playlists ADD COLUMN IF NOT EXISTS spotify_user_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_created_playlists_owner ON created_playlists (spotify_user_id, created_at);

CREATE TABLE IF NOT EXISTS user_used_tracks (
    id VARCHAR(100) PRIMARY KEY,
    spotify_user_id VARCHAR(100) NOT NULL,
    playlist_id VARCHAR(100),
    track_name VARCHAR(500) NOT NULL,
    artist_name VARCHAR(500) NOT NULL,
    normalized_key VARCHAR(1000) NOT NULL,
    spotify_uri VARCHAR(1000),
    created_at VARCHAR(100) NOT NULL,
    CONSTRAINT uq_user_used_tracks UNIQUE (spotify_user_id, normalized_key)
);

CREATE TABLE IF NOT EXISTS spotify_sessions (
    id VARCHAR(64) PRIMARY KEY,
    spotify_user_id VARCHAR(100) NOT NULL,
    refresh_token_enc VARCHAR(2000) NOT NULL,
    last_used_at BIGINT NOT NULL
);