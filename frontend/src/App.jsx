import { useEffect, useState } from "react";
import "./App.css";
const LOCAL_API_BASE_URL = "http://localhost:8080";
const PRODUCTION_API_BASE_URL = "https://spotify-bot-giik.onrender.com";

const isLocalFrontend =
    window.location.hostname === "localhost" ||
    window.location.hostname === "127.0.0.1";

const API_BASE_URL =
    import.meta.env.VITE_API_BASE_URL ||
    (isLocalFrontend ? LOCAL_API_BASE_URL : PRODUCTION_API_BASE_URL);

function apiUrl(path) {
    const cleanBaseUrl = API_BASE_URL.replace(/\/+$/, "");
    const cleanPath = path.startsWith("/") ? path : `/${path}`;

    return `${cleanBaseUrl}${cleanPath}`;
}
function App() {
    const [seedArtist, setSeedArtist] = useState("Che");
    const [playlistName, setPlaylistName] = useState("CheRandomizer");
    const [playlistSize, setPlaylistSize] = useState(20);
    const [restrictedArtists, setRestrictedArtists] = useState("");
    const [preferredArtists, setPreferredArtists] = useState("");
    const [preferredGenres, setPreferredGenres] = useState("");

    const [randomness, setRandomness] = useState(0.7);
    const [avoidUsedTracks, setAvoidUsedTracks] = useState(true);
    const [popularityScale, setPopularityScale] = useState(0.5);
    const [similarityScale, setSimilarityScale] = useState(0.5);
    const [advancedOpen, setAdvancedOpen] = useState(false);

    const [spotifyConnected, setSpotifyConnected] = useState(false);

    const [seedArtistOptions, setSeedArtistOptions] = useState([]);
    const [selectedSeedArtist, setSelectedSeedArtist] = useState(null);
    const [artistSearchLoading, setArtistSearchLoading] = useState(false);
    const [artistSearchError, setArtistSearchError] = useState("");

    const [playlist, setPlaylist] = useState(null);
    const [createdPlaylist, setCreatedPlaylist] = useState(null);
    const [playlistHistory, setPlaylistHistory] = useState([]);
    const [lastGenerationRequest, setLastGenerationRequest] = useState(null);

    const [loading, setLoading] = useState(false);
    const [creating, setCreating] = useState(false);
    const [replacingTrackIndex, setReplacingTrackIndex] = useState(null);

    const [error, setError] = useState("");
    const [createError, setCreateError] = useState("");
    const [replaceError, setReplaceError] = useState("");

    const hiddenNicheArtistScale = 0;

    const presets = [
        {
            name: "Deep Cuts",
            description: "Obscure songs, balanced distance",
            popularityScale: 0.9,
            similarityScale: 0.5,
            randomness: 0.35,
        },
        {
            name: "Nearby Underground",
            description: "Obscure songs close to seed",
            popularityScale: 0.9,
            similarityScale: 0.8,
            randomness: 0.3,
        },
        {
            name: "Popular Similar",
            description: "Bigger songs close to seed",
            popularityScale: 0.15,
            similarityScale: 0.85,
            randomness: 0.2,
        },
        {
            name: "Far Discovery",
            description: "Obscure songs farther away",
            popularityScale: 0.8,
            similarityScale: 0.15,
            randomness: 0.45,
        },
        {
            name: "Chaos Mode",
            description: "Balanced streams, high randomness",
            popularityScale: 0.5,
            similarityScale: 0.5,
            randomness: 1,
        },
    ];

    function splitInput(value) {
        return value
            .split(",")
            .map((item) => item.trim())
            .filter((item) => item.length > 0);
    }

    function createGenerationSeed() {
        if (window.crypto?.randomUUID) {
            return window.crypto.randomUUID();
        }

        return `${Date.now()}-${Math.random()}`;
    }

    function backendOfflineMessage() {
        return `Could not reach the backend at ${API_BASE_URL}. Please try again in a moment.`;
    }

    async function readApiError(response, fallbackMessage) {
        try {
            const contentType = response.headers.get("content-type") || "";

            if (contentType.includes("application/json")) {
                const data = await response.json();

                if (data?.message) {
                    return data.message;
                }

                if (data?.error) {
                    return data.error;
                }
            }

            const text = await response.text();

            if (text && text.trim()) {
                return text;
            }

            return fallbackMessage;
        } catch (err) {
            return fallbackMessage;
        }
    }

    function messageFromError(err, fallbackMessage) {
        if (err instanceof TypeError && err.message === "Failed to fetch") {
            return backendOfflineMessage();
        }

        return err?.message || fallbackMessage;
    }

    function formatNumber(value) {
        const number = Number(value);

        if (!Number.isFinite(number) || number <= 0) {
            return "Unknown";
        }

        return number.toLocaleString();
    }

    function formatSimilarity(value) {
        const number = Number(value);

        if (!Number.isFinite(number) || number <= 0) {
            return "Unknown";
        }

        const normalized = number > 1 ? number / 100 : number;

        return `${Math.round(normalized * 100)}%`;
    }

    function formatSliderValue(value) {
        const number = Number(value);

        if (!Number.isFinite(number)) {
            return "0.0";
        }

        return number.toFixed(1);
    }

    function formatPopularityScale(value) {
        const number = Number(value);

        if (number <= 0.2) {
            return `${formatSliderValue(value)} — Much more popular`;
        }

        if (number < 0.5) {
            return `${formatSliderValue(value)} — More popular`;
        }

        if (number === 0.5) {
            return `${formatSliderValue(value)} — Balanced`;
        }

        if (number < 0.8) {
            return `${formatSliderValue(value)} — More obscure`;
        }

        return `${formatSliderValue(value)} — Much more obscure`;
    }

    function formatSimilarityScale(value) {
        const number = Number(value);

        if (number <= 0.2) {
            return `${formatSliderValue(value)} — Much farther`;
        }

        if (number < 0.5) {
            return `${formatSliderValue(value)} — Farther`;
        }

        if (number === 0.5) {
            return `${formatSliderValue(value)} — Balanced`;
        }

        if (number < 0.8) {
            return `${formatSliderValue(value)} — Closer`;
        }

        return `${formatSliderValue(value)} — Much closer`;
    }

    function spotifyTrackUrl(track) {
        if (!track?.spotifyUri) {
            return "";
        }

        const parts = track.spotifyUri.split(":");
        const trackId = parts[parts.length - 1];

        if (!trackId) {
            return "";
        }

        return `https://open.spotify.com/track/${trackId}`;
    }

    function trackIdentity(item) {
        if (!item?.track) {
            return "";
        }

        if (item.track.spotifyUri) {
            return item.track.spotifyUri;
        }

        return `${item.track.artistName || ""}|${item.track.name || ""}`
            .toLowerCase()
            .replaceAll(/[^a-z0-9|]+/g, " ")
            .trim();
    }

    function buildGenerationRequest() {
        return {
            seedArtist,
            seedSpotifyArtistId: selectedSeedArtist?.id || "",
            playlistName,
            playlistSize: Number(playlistSize),
            restrictedArtists: splitInput(restrictedArtists),
            preferredArtists: splitInput(preferredArtists),
            preferredGenres: splitInput(preferredGenres),
            randomness: Number(randomness),
            generationSeed: createGenerationSeed(),
            avoidUsedTracks,
            nicheArtistScale: hiddenNicheArtistScale,
            nicheSongScale: Number(popularityScale),
            similarityScale: Number(similarityScale),
        };
    }

    function applyPreset(preset) {
        setPopularityScale(preset.popularityScale);
        setSimilarityScale(preset.similarityScale);
        setRandomness(preset.randomness);
        setCreatedPlaylist(null);
        setError("");
        setCreateError("");
        setReplaceError("");
    }

    function handleSeedArtistChange(event) {
        setSeedArtist(event.target.value);
        setSelectedSeedArtist(null);
        setSeedArtistOptions([]);
        setArtistSearchError("");
        setPlaylist(null);
        setCreatedPlaylist(null);
        setLastGenerationRequest(null);
    }

    async function checkSpotifyStatus() {
        try {
            const response = await fetch(apiUrl("/api/spotify/status"));

            if (!response.ok) {
                setSpotifyConnected(false);
                return;
            }

            const data = await response.json();
            setSpotifyConnected(data.connected);
        } catch (err) {
            setSpotifyConnected(false);
        }
    }

    async function fetchPlaylistHistory() {
        try {
            const response = await fetch(apiUrl("/api/playlists/history"));

            if (!response.ok) {
                return;
            }

            const data = await response.json();
            setPlaylistHistory(data);
        } catch (err) {
            setPlaylistHistory([]);
        }
    }

    async function clearPlaylistHistory() {
        try {
            await fetch(apiUrl("/api/playlists/history"), {
                method: "DELETE",
            });

            setPlaylistHistory([]);
        } catch (err) {
            // Keep quiet for now.
        }
    }

    async function searchSeedArtists() {
        if (!seedArtist.trim()) {
            setArtistSearchError("Enter a seed artist first.");
            return;
        }

        if (!spotifyConnected) {
            setArtistSearchError("Connect Spotify before searching artist matches.");
            return;
        }

        setArtistSearchLoading(true);
        setArtistSearchError("");
        setSelectedSeedArtist(null);

        try {
            const response = await fetch(
                apiUrl(
                    `/api/spotify/artists/search?query=${encodeURIComponent(seedArtist)}`
                )
            );

            if (!response.ok) {
                const message = await readApiError(response, "Artist search failed.");
                throw new Error(message);
            }

            const data = await response.json();
            setSeedArtistOptions(Array.isArray(data) ? data.slice(0, 5) : []);

            if (!Array.isArray(data) || data.length === 0) {
                setArtistSearchError("No matching Spotify artists found.");
            }
        } catch (err) {
            setArtistSearchError(messageFromError(err, "Could not search Spotify artists."));
        } finally {
            setArtistSearchLoading(false);
        }
    }

    function connectSpotify() {
        window.location.href = apiUrl("/api/spotify/login");
    }

    useEffect(() => {
        checkSpotifyStatus();
        fetchPlaylistHistory();

        const intervalId = setInterval(checkSpotifyStatus, 3000);

        function handleFocus() {
            checkSpotifyStatus();
            fetchPlaylistHistory();
        }

        window.addEventListener("focus", handleFocus);

        return () => {
            clearInterval(intervalId);
            window.removeEventListener("focus", handleFocus);
        };
    }, []);

    async function generatePlaylist(event) {
        event.preventDefault();

        if (!spotifyConnected) {
            setError("Spotify is not connected. Click Connect Spotify before generating.");
            return;
        }

        if (!selectedSeedArtist) {
            setError("Search and select the correct Spotify seed artist before generating.");
            return;
        }

        setLoading(true);
        setError("");
        setCreateError("");
        setReplaceError("");
        setPlaylist(null);
        setCreatedPlaylist(null);

        const requestBody = buildGenerationRequest();

        try {
            const response = await fetch(apiUrl("/api/playlists/generate"), {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                },
                body: JSON.stringify(requestBody),
            });

            if (!response.ok) {
                const message = await readApiError(response, "Playlist generation failed.");
                throw new Error(message);
            }

            const data = await response.json();
            setPlaylist(data);
            setLastGenerationRequest(requestBody);

            if (!data?.tracks || data.tracks.length === 0) {
                setError("No Spotify-compatible tracks were found. Try a more popular preset or adjust advanced settings.");
            }
        } catch (err) {
            setError(messageFromError(err, "Could not generate playlist."));
        } finally {
            setLoading(false);
        }
    }

    async function replaceTrack(indexToReplace) {
        if (!playlist || !playlist.tracks[indexToReplace]) {
            return;
        }

        if (!lastGenerationRequest) {
            setReplaceError("Generate a playlist first before replacing tracks.");
            return;
        }

        if (!spotifyConnected) {
            setReplaceError("Spotify is not connected.");
            return;
        }

        setReplacingTrackIndex(indexToReplace);
        setReplaceError("");
        setCreatedPlaylist(null);

        const existingTrackIds = new Set(playlist.tracks.map(trackIdentity));

        const replacementRequest = {
            ...lastGenerationRequest,
            playlistSize: Math.min(50, Math.max(playlist.tracks.length + 5, 10)),
            generationSeed: createGenerationSeed(),
        };

        try {
            const response = await fetch(apiUrl("/api/playlists/generate"), {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                },
                body: JSON.stringify(replacementRequest),
            });

            if (!response.ok) {
                const message = await readApiError(response, "Replacement request failed.");
                throw new Error(message);
            }

            const data = await response.json();
            const candidates = Array.isArray(data.tracks) ? data.tracks : [];

            const replacement = candidates.find(
                (candidate) => !existingTrackIds.has(trackIdentity(candidate))
            );

            if (!replacement) {
                throw new Error(
                    "Could not find a fresh replacement. Try adjusting advanced settings or generating again."
                );
            }

            const updatedTracks = [...playlist.tracks];
            updatedTracks[indexToReplace] = replacement;

            setPlaylist({
                ...playlist,
                tracks: updatedTracks,
            });
        } catch (err) {
            setReplaceError(messageFromError(err, "Could not replace track."));
        } finally {
            setReplacingTrackIndex(null);
        }
    }

    async function createPlaylist() {
        if (!playlist || playlist.tracks.length === 0) {
            return;
        }

        if (!spotifyConnected) {
            setCreateError("Spotify is not connected. Click Connect Spotify first.");
            return;
        }

        setCreating(true);
        setCreateError("");
        setCreatedPlaylist(null);

        const requestBody = {
            playlistName: playlist.playlistName,
            seedArtist: playlist.seedArtist,
            tracks: playlist.tracks,
            reserveTracks: playlist.reserveTracks || [],
            targetTrackCount: playlist.tracks.length,
        };

        try {
            const response = await fetch(apiUrl("/api/playlists/create"), {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                },
                body: JSON.stringify(requestBody),
            });

            if (!response.ok) {
                const message = await readApiError(response, "Create playlist request failed.");
                throw new Error(message);
            }

            const data = await response.json();
            setCreatedPlaylist(data);
            checkSpotifyStatus();
            fetchPlaylistHistory();
        } catch (err) {
            setCreateError(messageFromError(err, "Could not create playlist."));
        } finally {
            setCreating(false);
        }
    }

    function removeTrack(trackId, spotifyUri, indexToRemove) {
        if (!playlist) {
            return;
        }

        const updatedTracks = playlist.tracks.filter((item, index) => {
            if (spotifyUri) {
                return item.track.spotifyUri !== spotifyUri;
            }

            if (trackId) {
                return item.track.id !== trackId;
            }

            return index !== indexToRemove;
        });

        setPlaylist({
            ...playlist,
            tracks: updatedTracks,
        });

        setCreatedPlaylist(null);
    }

    function clearPlaylist() {
        setPlaylist(null);
        setCreatedPlaylist(null);
        setCreateError("");
        setReplaceError("");
    }

    const artistPickerRowStyle = {
        display: "flex",
        flexDirection: "row",
        flexWrap: "nowrap",
        gap: "12px",
        width: "100%",
        maxWidth: "100%",
        overflowX: "auto",
        overflowY: "hidden",
        padding: "12px 0",
        alignItems: "stretch",
    };

    function artistCardStyle(artist) {
        const selected = selectedSeedArtist?.id === artist.id;

        return {
            flex: "0 0 120px",
            width: "120px",
            minWidth: "120px",
            maxWidth: "120px",
            height: "190px",
            boxSizing: "border-box",
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            justifyContent: "flex-start",
            gap: "6px",
            padding: "8px",
            border: selected
                ? "2px solid #1db954"
                : "1px solid rgba(255,255,255,0.22)",
            borderRadius: "14px",
            background: selected
                ? "rgba(29,185,84,0.18)"
                : "rgba(255,255,255,0.06)",
            color: "white",
            cursor: "pointer",
            textAlign: "center",
            boxShadow: selected ? "0 0 0 2px rgba(29,185,84,0.25)" : "none",
        };
    }

    const artistImageStyle = {
        width: "82px",
        height: "82px",
        minWidth: "82px",
        minHeight: "82px",
        maxWidth: "82px",
        maxHeight: "82px",
        borderRadius: "999px",
        objectFit: "cover",
        display: "block",
        flexShrink: 0,
    };

    const artistPlaceholderStyle = {
        ...artistImageStyle,
        display: "grid",
        placeItems: "center",
        background: "rgba(255,255,255,0.12)",
        fontSize: "28px",
        fontWeight: 700,
    };

    const artistNameStyle = {
        width: "100%",
        fontSize: "12px",
        lineHeight: "1.1",
        fontWeight: 700,
        overflowWrap: "anywhere",
    };

    const artistMetaStyle = {
        width: "100%",
        fontSize: "10px",
        lineHeight: "1.1",
        opacity: 0.78,
        overflowWrap: "anywhere",
    };

    return (
        <main className="page">
            <section className="hero">
                <h1>Spotify Bot Playlist Manager</h1>
                <p>Create a weighted playlist using Last.fm discovery and Spotify output.</p>

                <div className="spotify-status-card">
                    <p>
                        Spotify Status:{" "}
                        <strong className={spotifyConnected ? "connected" : "not-connected"}>
                            {spotifyConnected ? "Connected" : "Not Connected"}
                        </strong>
                    </p>

                    <div className="spotify-buttons">
                        <button type="button" onClick={connectSpotify}>
                            Connect Spotify
                        </button>

                        <button
                            type="button"
                            className="secondary-button"
                            onClick={checkSpotifyStatus}
                        >
                            Refresh Status
                        </button>
                    </div>
                </div>
            </section>

            <section className="panel">
                <form onSubmit={generatePlaylist} className="form">
                    <label>
                        Seed Artist
                        <input
                            value={seedArtist}
                            onChange={handleSeedArtistChange}
                            placeholder="Daft Punk"
                        />
                    </label>

                    <div className="seed-artist-search">
                        <button
                            type="button"
                            className="secondary-button"
                            onClick={searchSeedArtists}
                            disabled={artistSearchLoading || !spotifyConnected}
                        >
                            {artistSearchLoading ? "Searching..." : "Find Matching Artists"}
                        </button>

                        {selectedSeedArtist && (
                            <p className="selected-seed-artist">
                                Selected seed artist: <strong>{selectedSeedArtist.name}</strong>
                            </p>
                        )}

                        {artistSearchError && <p className="error">{artistSearchError}</p>}
                    </div>

                    {seedArtistOptions.length > 0 && (
                        <div style={artistPickerRowStyle}>
                            {seedArtistOptions.slice(0, 5).map((artist) => (
                                <div
                                    key={artist.id}
                                    role="button"
                                    tabIndex={0}
                                    style={artistCardStyle(artist)}
                                    onClick={() => setSelectedSeedArtist(artist)}
                                    onKeyDown={(event) => {
                                        if (event.key === "Enter" || event.key === " ") {
                                            event.preventDefault();
                                            setSelectedSeedArtist(artist);
                                        }
                                    }}
                                >
                                    {artist.imageUrl ? (
                                        <img
                                            src={artist.imageUrl}
                                            alt={artist.name}
                                            style={artistImageStyle}
                                        />
                                    ) : (
                                        <div style={artistPlaceholderStyle}>?</div>
                                    )}

                                    <strong style={artistNameStyle}>{artist.name}</strong>

                                    <span style={artistMetaStyle}>
                    {formatNumber(artist.followers)} followers
                  </span>

                                    <small style={artistMetaStyle}>
                                        {artist.genres && artist.genres.length > 0
                                            ? artist.genres.slice(0, 3).join(", ")
                                            : "No genres listed"}
                                    </small>
                                </div>
                            ))}
                        </div>
                    )}

                    <label>
                        Playlist Name
                        <input
                            value={playlistName}
                            onChange={(event) => setPlaylistName(event.target.value)}
                            placeholder="Niche Discovery Test"
                        />
                    </label>

                    <label>
                        Playlist Size
                        <input
                            type="number"
                            min="1"
                            max="50"
                            value={playlistSize}
                            onChange={(event) => setPlaylistSize(event.target.value)}
                        />
                    </label>

                    <div>
                        <p>Presets</p>

                        <div className="spotify-buttons">
                            {presets.map((preset) => (
                                <button
                                    key={preset.name}
                                    type="button"
                                    className="secondary-button"
                                    onClick={() => applyPreset(preset)}
                                    title={preset.description}
                                >
                                    {preset.name}
                                </button>
                            ))}
                        </div>
                    </div>

                    <label className="checkbox-label">
                        <input
                            type="checkbox"
                            checked={avoidUsedTracks}
                            onChange={(event) => setAvoidUsedTracks(event.target.checked)}
                        />
                        Avoid songs already used in previous playlists
                    </label>

                    <button
                        type="button"
                        className="secondary-button"
                        onClick={() => setAdvancedOpen((current) => !current)}
                    >
                        {advancedOpen ? "Hide Advanced Settings" : "Show Advanced Settings"}
                    </button>

                    {!advancedOpen && (
                        <p className="helper-text">
                            Advanced filters and sliders are hidden. Current settings:
                            popularity {formatPopularityScale(popularityScale)}, similarity{" "}
                            {formatSimilarityScale(similarityScale)}, randomness{" "}
                            {formatSliderValue(randomness)}.
                        </p>
                    )}

                    {advancedOpen && (
                        <div className="advanced-settings">
                            <label>
                                Restricted Artists
                                <input
                                    value={restrictedArtists}
                                    onChange={(event) => setRestrictedArtists(event.target.value)}
                                    placeholder="Artist One, Artist Two"
                                />
                            </label>

                            <label>
                                Preferred Artists
                                <input
                                    value={preferredArtists}
                                    onChange={(event) => setPreferredArtists(event.target.value)}
                                    placeholder="Justice, Kavinsky"
                                />
                            </label>

                            <label>
                                Preferred Genres / Tags
                                <input
                                    value={preferredGenres}
                                    onChange={(event) => setPreferredGenres(event.target.value)}
                                    placeholder="electronic, dance, house"
                                />
                            </label>

                            <label>
                                Randomness: {formatSliderValue(randomness)}
                                <input
                                    type="range"
                                    min="0"
                                    max="1"
                                    step="0.1"
                                    value={randomness}
                                    onChange={(event) => setRandomness(event.target.value)}
                                />
                            </label>

                            <label>
                                Popularity: {formatPopularityScale(popularityScale)}
                                <input
                                    type="range"
                                    min="0"
                                    max="1"
                                    step="0.1"
                                    value={popularityScale}
                                    onChange={(event) => setPopularityScale(event.target.value)}
                                />
                                <span>High Streams &larr; Balanced &rarr; Low Streams</span>
                            </label>

                            <label>
                                Similarity to Seed Artist: {formatSimilarityScale(similarityScale)}
                                <input
                                    type="range"
                                    min="0"
                                    max="1"
                                    step="0.1"
                                    value={similarityScale}
                                    onChange={(event) => setSimilarityScale(event.target.value)}
                                />
                                <span>Farther &larr; Balanced &rarr; Closer</span>
                            </label>
                        </div>
                    )}

                    <button
                        type="submit"
                        disabled={loading || !spotifyConnected || !selectedSeedArtist}
                    >
                        {loading ? "Generating..." : "Generate Playlist"}
                    </button>

                    {!selectedSeedArtist && (
                        <p className="helper-text">
                            Search and select the correct Spotify seed artist before generating.
                        </p>
                    )}

                    {error && <p className="error">{error}</p>}
                </form>
            </section>

            {playlist && (
                <section className="playlist">
                    <div className="playlist-title-row">
                        <div>
                            <h2>{playlist.playlistName}</h2>
                            <p>Seed artist: {playlist.seedArtist}</p>
                            <p>Tracks selected: {playlist.tracks.length}</p>
                        </div>

                        <div className="playlist-actions">
                            <button
                                onClick={createPlaylist}
                                disabled={creating || playlist.tracks.length === 0 || !spotifyConnected}
                            >
                                {creating ? "Creating..." : "Create Playlist"}
                            </button>

                            <button className="secondary-button" onClick={clearPlaylist}>
                                Clear
                            </button>
                        </div>
                    </div>

                    {createError && <p className="error">{createError}</p>}
                    {replaceError && <p className="error">{replaceError}</p>}

                    {playlist.tracks.length === 0 && (
                        <p className="error">
                            No tracks selected. Generate again or adjust your filters.
                        </p>
                    )}

                    {createdPlaylist && (
                        <div className="success-card">
                            <h3>{createdPlaylist.status}</h3>
                            <p>Playlist: {createdPlaylist.playlistName}</p>
                            <p>Tracks added: {createdPlaylist.trackCount}</p>
                            <a
                                href={createdPlaylist.spotifyPlaylistUrl}
                                target="_blank"
                                rel="noreferrer"
                            >
                                Open Spotify Playlist
                            </a>
                        </div>
                    )}

                    <div className="track-list">
                        {playlist.tracks.map((item, index) => {
                            const spotifyUrl = spotifyTrackUrl(item.track);

                            return (
                                <div
                                    className="track-card"
                                    key={`${item.track.spotifyUri || item.track.id || item.track.name}-${index}`}
                                >
                                    <div className="track-header">
                                        <span className="rank">#{index + 1}</span>

                                        <div>
                                            <h3>{item.track.name}</h3>
                                            <p>{item.track.artistName}</p>
                                        </div>
                                    </div>

                                    <p className="score">Score: {Number(item.score).toFixed(2)}</p>

                                    {spotifyUrl && (
                                        <p>
                                            <a href={spotifyUrl} target="_blank" rel="noreferrer">
                                                Open in Spotify
                                            </a>
                                        </p>
                                    )}

                                    <div className="genres">
                                        {(item.track.genres || []).map((genre) => (
                                            <span key={genre}>{genre}</span>
                                        ))}
                                    </div>

                                    <div className="metadata">
                                        <p>
                                            <strong>Track plays:</strong>{" "}
                                            {formatNumber(item.track.trackPlaycount)}
                                        </p>

                                        <p>
                                            <strong>Track listeners:</strong>{" "}
                                            {formatNumber(item.track.trackListeners)}
                                        </p>

                                        <p>
                                            <strong>Artist plays:</strong>{" "}
                                            {formatNumber(item.track.artistPlaycount)}
                                        </p>

                                        <p>
                                            <strong>Artist listeners:</strong>{" "}
                                            {formatNumber(item.track.artistListeners)}
                                        </p>

                                        <p>
                                            <strong>Artist similarity:</strong>{" "}
                                            {formatSimilarity(item.track.artistSimilarity)}
                                        </p>

                                        <p>
                                            <strong>Spotify URI:</strong>{" "}
                                            {item.track.spotifyUri ? "Validated" : "Missing"}
                                        </p>
                                    </div>

                                    <ul>
                                        {(item.reasons || []).map((reason) => (
                                            <li key={reason}>{reason}</li>
                                        ))}
                                    </ul>

                                    <div className="spotify-buttons">
                                        <button
                                            type="button"
                                            className="secondary-button"
                                            onClick={() => replaceTrack(index)}
                                            disabled={replacingTrackIndex !== null}
                                        >
                                            {replacingTrackIndex === index ? "Replacing..." : "Replace Track"}
                                        </button>

                                        <button
                                            className="remove-button"
                                            onClick={() =>
                                                removeTrack(item.track.id, item.track.spotifyUri, index)
                                            }
                                        >
                                            Remove Track
                                        </button>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                </section>
            )}

            <section className="playlist history-section">
                <div className="playlist-title-row">
                    <div>
                        <h2>Playlist History</h2>
                        <p>Recently created Spotify playlists from this session.</p>
                    </div>

                    <div className="playlist-actions">
                        <button className="secondary-button" onClick={fetchPlaylistHistory}>
                            Refresh History
                        </button>

                        <button className="remove-button" onClick={clearPlaylistHistory}>
                            Clear History
                        </button>
                    </div>
                </div>

                {playlistHistory.length === 0 && (
                    <p className="empty-history">No playlists created yet.</p>
                )}

                <div className="history-list">
                    {playlistHistory.map((item) => (
                        <div className="history-card" key={item.id}>
                            <h3>{item.playlistName}</h3>
                            <p>Seed artist: {item.seedArtist || "Unknown"}</p>
                            <p>Tracks added: {item.trackCount}</p>
                            <p>Created: {new Date(item.createdAt).toLocaleString()}</p>

                            <a href={item.spotifyPlaylistUrl} target="_blank" rel="noreferrer">
                                Open in Spotify
                            </a>
                        </div>
                    ))}
                </div>
            </section>
        </main>
    );
}

export default App;
