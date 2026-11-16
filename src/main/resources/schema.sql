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

-- When Spotify last told RabbitHole to stop sending requests (one row, id 1), so a restart remembers it.
CREATE TABLE IF NOT EXISTS spotify_pause (
    id INT PRIMARY KEY,
    paused_until BIGINT NOT NULL
);

-- Songs already looked up on Spotify, keyed by a hash of the lookup, so restarts don't repeat searches.
-- A null spotify_uri records that Spotify had no match.
CREATE TABLE IF NOT EXISTS spotify_track_matches (
    id VARCHAR(64) PRIMARY KEY,
    spotify_uri VARCHAR(100),
    checked_at BIGINT NOT NULL
);

-- Artist search results as JSON, keyed by a hash of the search, so repeat searches skip Spotify.
CREATE TABLE IF NOT EXISTS spotify_artist_searches (
    id VARCHAR(64) PRIMARY KEY,
    results VARCHAR(20000) NOT NULL,
    checked_at BIGINT NOT NULL
);

-- Usage counts kept across restarts: the daily Spotify allowance ("spotify-hour:<hour start>") and
-- the site-wide hourly limits ("site:<route>"). window_start is when the counted window began.
CREATE TABLE IF NOT EXISTS usage_counters (
    name VARCHAR(100) PRIMARY KEY,
    window_start BIGINT NOT NULL,
    request_count INT NOT NULL
);