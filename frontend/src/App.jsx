import { useEffect, useState } from "react";
import "./App.css";

import spotifyIcon from "./assets/spotify-icon.png";
<img src={spotifyIcon} alt="Spotify" />

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

const SESSION_STORAGE_KEY = "spotifyBotSession";
let sessionTokenInMemory = "";

function readSessionToken() {
    try {
        return window.localStorage.getItem(SESSION_STORAGE_KEY) || sessionTokenInMemory;
    } catch {
        return sessionTokenInMemory;
    }
}

function writeSessionToken(token) {
    sessionTokenInMemory = token;

    try {
        if (token) {
            window.localStorage.setItem(SESSION_STORAGE_KEY, token);
        } else {
            window.localStorage.removeItem(SESSION_STORAGE_KEY);
        }
    } catch {
        // Storage can be blocked; the session then lasts until the page is closed.
    }
}

// The backend redirects here after Spotify login with either #session=... or ?spotify=error.
// Both are removed from the address bar straight away so the token is not left in history.
function consumeSpotifyLoginRedirect() {
    const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ""));
    const query = new URLSearchParams(window.location.search);
    const session = fragment.get("session");

    if (session) {
        writeSessionToken(session);
    }

    if (session || query.has("spotify")) {
        window.history.replaceState(null, "", window.location.pathname);
    }

    return query.get("spotify") === "error"
        ? query.get("message") || "Spotify connection failed. Please try again."
        : "";
}

const initialLoginError = consumeSpotifyLoginRedirect();

function createGenerationSeed() {
    if (window.crypto?.randomUUID) {
        return window.crypto.randomUUID();
    }

    return `${Date.now()}-${Math.random()}`;
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
<<<<<<< HEAD
=======
    const [loginError, setLoginError] = useState(initialLoginError);
    const [wizardStage, setUiStage] = useState(2);
    const uiStage = spotifyConnected ? wizardStage : 1;
>>>>>>> 0dd369d (bug fixes, backend improvements, database inconsistencies)

    const [seedArtistOptions, setSeedArtistOptions] = useState([]);
    const [selectedSeedArtist, setSelectedSeedArtist] = useState(null);
    const [artistSearchLoading, setArtistSearchLoading] = useState(false);
    const [artistSearchError, setArtistSearchError] = useState("");

    const [playlist, setPlaylist] = useState(null);
    const [createdPlaylist, setCreatedPlaylist] = useState(null);
    const [playlistHistory, setPlaylistHistory] = useState([]);
    const visibleHistory = spotifyConnected ? playlistHistory : [];
    const [lastGenerationRequest, setLastGenerationRequest] = useState(null);

    const [loading, setLoading] = useState(false);
    const [creating, setCreating] = useState(false);
    const [replacingTrackIndex, setReplacingTrackIndex] = useState(null);

    const [error, setError] = useState("");
    const [createError, setCreateError] = useState("");
    const [replaceError, setReplaceError] = useState("");
    const [historyError, setHistoryError] = useState("");
    const [generationNotice, setGenerationNotice] = useState("");

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

    async function apiFetch(path, options = {}) {
        const token = readSessionToken();
        const headers = { ...options.headers };

        if (token) {
            headers.Authorization = `Bearer ${token}`;
        }

        const response = await fetch(apiUrl(path), { ...options, headers });

        if (response.status === 401) {
            writeSessionToken("");
            setSpotifyConnected(false);
            setLoginError("Your Spotify connection ended. Connect again to continue.");
        }

        return response;
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
        } catch {
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

    // A failed check says nothing about the connection, so it leaves the current state alone.
    async function checkSpotifyStatus() {
        try {
            const response = await apiFetch("/api/spotify/status");

            if (!response.ok) {
                return;
            }

            const data = await response.json();

            if (!data.connected) {
                writeSessionToken("");
            }

            setSpotifyConnected(data.connected);

            if (data.connected) {
                fetchPlaylistHistory();
            }
        } catch {
            // The backend may be waking up; the next focus event checks again.
        }
    }

    async function fetchPlaylistHistory() {
        if (!readSessionToken()) {
            return;
        }

        try {
            const response = await apiFetch("/api/playlists/history");

            if (!response.ok) {
                return;
            }

            const data = await response.json();
            setPlaylistHistory(data);
            setHistoryError("");
        } catch (err) {
            setHistoryError(messageFromError(err, "Could not load playlist history."));
        }
    }

    async function clearPlaylistHistory() {
        if (!window.confirm("Clear your playlist history? Songs you have used could be picked again.")) {
            return;
        }

        setHistoryError("");

        try {
            const response = await apiFetch("/api/playlists/history", {
                method: "DELETE",
            });

            if (!response.ok) {
                const message = await readApiError(response, "Could not clear playlist history.");
                throw new Error(message);
            }

            setPlaylistHistory([]);
        } catch (err) {
            setHistoryError(messageFromError(err, "Could not clear playlist history."));
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
            const response = await apiFetch(
                `/api/spotify/artists/search?query=${encodeURIComponent(seedArtist)}`
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
        setLoginError("");
        window.location.href = apiUrl("/api/spotify/login");
    }

    async function disconnectSpotify() {
        try {
            await apiFetch("/api/spotify/logout", { method: "POST" });
        } catch {
            // Still sign out of this browser if the backend cannot be reached.
        }

        writeSessionToken("");
        setSpotifyConnected(false);
        setPlaylistHistory([]);
        clearPlaylist();
        setSelectedSeedArtist(null);
        setSeedArtistOptions([]);
        setLastGenerationRequest(null);
        setUiStage(2);
    }

    useEffect(() => {
        function handleFocus() {
            checkSpotifyStatus();
        }

        handleFocus();
        window.addEventListener("focus", handleFocus);

        return () => {
            window.removeEventListener("focus", handleFocus);
        };
        // Runs once on mount; the check only reads storage and calls state setters.
        // eslint-disable-next-line react-hooks/exhaustive-deps
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
        setGenerationNotice("");
        setPlaylist(null);
        setCreatedPlaylist(null);

        const requestBody = buildGenerationRequest();

        try {
            const response = await apiFetch("/api/playlists/generate", {
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
            } else if (data.tracks.length < requestBody.playlistSize) {
                setGenerationNotice(
                    `Found ${data.tracks.length} of the ${requestBody.playlistSize} tracks you asked for. A more popular preset usually finds more.`
                );
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

        try {
            const response = await apiFetch("/api/playlists/replace-track", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                },
                body: JSON.stringify({
                    generationRequest: lastGenerationRequest,
                    currentTracks: playlist.tracks,
                    trackToReplace: playlist.tracks[indexToReplace],
                }),
            });

            if (!response.ok) {
                const message = await readApiError(response, "Replacement request failed.");
                throw new Error(message);
            }

            const data = await response.json();

            const updatedTracks = [...playlist.tracks];
            updatedTracks[indexToReplace] = data.replacementTrack;

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
            const response = await apiFetch("/api/playlists/create", {
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
        setGenerationNotice("");
    }

    return (
        <main>
            <h1>Spotify Bot</h1>
            <p>
                Backend: <code>{API_BASE_URL}</code>
            </p>

            <section>
                <h2>Spotify</h2>
                <p>
                    Status: <strong>{spotifyConnected ? "Connected" : "Not connected"}</strong>
                </p>
                <button type="button"

                        className={spotifyConnected ? "spotify-connected-button" : "spotify-disconnected-button"}
                        onClick={connectSpotify}>
                         <span className="spotify-button-text">
                             {spotifyConnected ? "Connected" : "Connect Spotify"}
                          </span>
                    <img src={spotifyIcon} alt="Spotify" />
                </button>


            </section>

            <hr />

            <section>
                <h2>Build Playlist</h2>

                <form onSubmit={generatePlaylist}>
                    <label htmlFor="seedArtist">Seed Artist</label>
                    <input
                        id="seedArtist"
                        value={seedArtist}
                        onChange={handleSeedArtistChange}
                        placeholder="Daft Punk"
                    />

                    <button
                        className="wide-button"
                        type="button"
                        onClick={searchSeedArtists}
                        disabled={artistSearchLoading || !spotifyConnected}
                    >
                        {artistSearchLoading ? "Searching..." : "Find Matching Artists"}
                    </button>
<<<<<<< HEAD
=======

                    {spotifyConnected && (
                        <button
                            type="button"
                            className="button button-secondary small-button disconnect-button"
                            onClick={disconnectSpotify}
                        >
                            Disconnect
                        </button>
                    )}

                    {loginError && <p className="error">{loginError}</p>}
                </section>
>>>>>>> 0dd369d (bug fixes, backend improvements, database inconsistencies)

                    {artistSearchError && <p className="error">{artistSearchError}</p>}

                    {seedArtistOptions.length > 0 && (
                        <div>
<<<<<<< HEAD
                            <p>Select the correct Spotify artist:</p>
                            {seedArtistOptions.map((artist) => (
                                <button
                                    key={artist.id}
                                    type="button"
                                    className={selectedSeedArtist?.id === artist.id ? "selected" : ""}
                                    onClick={() => setSelectedSeedArtist(artist)}
                                >
                                    {artist.name} — {formatNumber(artist.followers)} followers
                                </button>
                            ))}
=======
                            <p className="eyebrow">GENERATED PLAYLIST</p>
                            <h2>{playlist.playlistName}</h2>
                            <p>
                                {playlist.tracks.length} tracks · Seeded by {playlist.seedArtist}
                            </p>
                        </div>

                        <div className="results-actions">
                            <button
                                type="button"
                                className="button button-primary"
                                onClick={createPlaylist}
                                disabled={creating || playlist.tracks.length === 0 || !spotifyConnected}
                            >
                                {creating ? "Creating..." : "Create on Spotify"}
                            </button>

                            <button
                                type="button"
                                className="button button-secondary"
                                onClick={clearPlaylist}
                            >
                                Clear
                            </button>
                        </div>
                    </div>

                    {generationNotice && <p className="notice">{generationNotice}</p>}
                    {createError && <p className="error">{createError}</p>}
                    {replaceError && <p className="error">{replaceError}</p>}

                    {createdPlaylist && (
                        <div className="success">
                            <strong>{createdPlaylist.status}</strong>
                            <span>{createdPlaylist.playlistName}</span>
                            <span>{createdPlaylist.trackCount} tracks added</span>
                            <a
                                href={createdPlaylist.spotifyPlaylistUrl}
                                target="_blank"
                                rel="noreferrer"
                            >
                                Open Spotify Playlist ↗
                            </a>
>>>>>>> 0dd369d (bug fixes, backend improvements, database inconsistencies)
                        </div>
                    )}

                    {selectedSeedArtist && (
                        <p>Selected: <strong>{selectedSeedArtist.name}</strong></p>
                    )}

                    <label htmlFor="playlistName">Playlist Name</label>
                    <input
                        id="playlistName"
                        value={playlistName}
                        onChange={(event) => setPlaylistName(event.target.value)}
                    />

                    <label htmlFor="playlistSize">Playlist Size</label>
                    <input
                        id="playlistSize"
                        type="number"
                        min="1"
                        max="50"
                        value={playlistSize}
                        onChange={(event) => setPlaylistSize(event.target.value)}
                    />
                    <input
                        type="range"
                        min="1"
                        max="50"
                        value={playlistSize}
                        onChange={(e) => setPlaylistSize(e.target.value)}
                        />
                    <p>Presets</p>
                    <div>
                        {presets.map((preset) => (
                            <button className="preset-buttons"
                                key={preset.name}
                                type="button"
                                onClick={() => applyPreset(preset)}
                                title={preset.description}
                            >
                                {preset.name}
                            </button>
                        ))}
                    </div>

                    <label className="checkbox-row">
                        <input
                            type="checkbox"
                            checked={avoidUsedTracks}
                            onChange={(event) => setAvoidUsedTracks(event.target.checked)}
                        />
                        Avoid songs already used
                    </label>

                    <button
                        className="wide-button"
                        type="button"
                        onClick={() => setAdvancedOpen((current) => !current)}
                    >
                        {advancedOpen ? "Hide Advanced Settings" : "Show Advanced Settings"}
                    </button>

                    {advancedOpen && (
                        <div>
                            <label htmlFor="restrictedArtists">Restricted Artists</label>
                            <input
                                id="restrictedArtists"
                                value={restrictedArtists}
                                onChange={(event) => setRestrictedArtists(event.target.value)}
                                placeholder="Artist One, Artist Two"
                            />

                            <label htmlFor="preferredArtists">Preferred Artists</label>
                            <input
                                id="preferredArtists"
                                value={preferredArtists}
                                onChange={(event) => setPreferredArtists(event.target.value)}
                                placeholder="Justice, Kavinsky"
                            />

                            <label htmlFor="preferredGenres">Preferred Genres / Tags</label>
                            <input
                                id="preferredGenres"
                                value={preferredGenres}
                                onChange={(event) => setPreferredGenres(event.target.value)}
                                placeholder="electronic, dance, house"
                            />

                            <label htmlFor="randomness">Randomness: {Number(randomness).toFixed(1)}</label>
                            <input
                                id="randomness"
                                type="range"
                                min="0"
                                max="1"
                                step="0.1"
                                value={randomness}
                                onChange={(event) => setRandomness(event.target.value)}
                            />

                            <label htmlFor="popularity">Popularity / obscurity: {Number(popularityScale).toFixed(1)}</label>
                            <input
                                id="popularity"
                                type="range"
                                min="0"
                                max="1"
                                step="0.1"
                                value={popularityScale}
                                onChange={(event) => setPopularityScale(event.target.value)}
                            />

                            <label htmlFor="similarity">Similarity to seed: {Number(similarityScale).toFixed(1)}</label>
                            <input
                                id="similarity"
                                type="range"
                                min="0"
                                max="1"
                                step="0.1"
                                value={similarityScale}
                                onChange={(event) => setSimilarityScale(event.target.value)}
                            />
                        </div>
                    )}

                    <button
                        type="submit"

                        className="wide-button"
                        disabled={loading || !spotifyConnected || !selectedSeedArtist}
                    >
                        {loading ? "Generating..." : "Generate Playlist"}
                    </button>

                    {error && <p className="error">{error}</p>}
                </form>
            </section>

            {playlist && (
                <>
                    <hr />
                    <section>
                        <h2>{playlist.playlistName}</h2>
                        <p>Seed artist: {playlist.seedArtist}</p>
                        <p>Tracks: {playlist.tracks.length}</p>

                        <button
                            type="button"
                            onClick={createPlaylist}
                            disabled={creating || playlist.tracks.length === 0 || !spotifyConnected}
                        >
                            {creating ? "Creating..." : "Create Playlist on Spotify"}
                        </button>

                        <button type="button" onClick={clearPlaylist}>Clear Playlist</button>

                        {createError && <p className="error">{createError}</p>}
                        {replaceError && <p className="error">{replaceError}</p>}

                        {createdPlaylist && (
                            <div className="success">
                                <p><strong>{createdPlaylist.status}</strong></p>
                                <p>{createdPlaylist.playlistName}</p>
                                <p>Tracks added: {createdPlaylist.trackCount}</p>
                                <a href={createdPlaylist.spotifyPlaylistUrl} target="_blank" rel="noreferrer">
                                    Open Spotify Playlist
                                </a>
                            </div>
                        )}

                        <ol>
                            {playlist.tracks.map((item, index) => {
                                const spotifyUrl = spotifyTrackUrl(item.track);

                                return (
                                    <li key={`${item.track.spotifyUri || item.track.id || item.track.name}-${index}`}>
                                        <h3>{item.track.name}</h3>
                                        <p>{item.track.artistName}</p>

                                        <details>
                                            <summary>Track details</summary>
                                            <p>Score: {Number(item.score).toFixed(2)}</p>
                                            <p>Track plays: {formatNumber(item.track.trackPlaycount)}</p>
                                            <p>Track listeners: {formatNumber(item.track.trackListeners)}</p>
                                            <p>Artist plays: {formatNumber(item.track.artistPlaycount)}</p>
                                            <p>Artist listeners: {formatNumber(item.track.artistListeners)}</p>
                                            <p>Artist similarity: {formatSimilarity(item.track.artistSimilarity)}</p>
                                            {(item.track.genres || []).length > 0 && (
                                                <p>Genres: {item.track.genres.join(", ")}</p>
                                            )}
                                            {(item.reasons || []).length > 0 && (
                                                <ul>
                                                    {item.reasons.map((reason) => <li key={reason}>{reason}</li>)}
                                                </ul>
                                            )}
                                        </details>

                                        {spotifyUrl && (
                                            <a href={spotifyUrl} target="_blank" rel="noreferrer">
                                                Open Track in Spotify
                                            </a>
                                        )}

                                        <div>
                                            <button
                                                type="button"
                                                onClick={() => replaceTrack(index)}
                                                disabled={replacingTrackIndex !== null}
                                            >
                                                {replacingTrackIndex === index ? "Replacing..." : "Replace"}
                                            </button>
                                            <button
                                                type="button"
                                                onClick={() => removeTrack(item.track.id, item.track.spotifyUri, index)}
                                            >
                                                Remove
                                            </button>
                                        </div>
                                    </li>
                                );
                            })}
                        </ol>
                    </section>
                </>
            )}

            <hr />

<<<<<<< HEAD
            <form>
                <h2>Playlist History</h2>
                <button type="button" onClick={fetchPlaylistHistory}>Refresh History</button>
                <button type="button" onClick={clearPlaylistHistory}>Clear History</button>

                {playlistHistory.length === 0 ? (
                    <p>No playlists created yet.</p>
                ) : (
                    <ul>
                        {playlistHistory.map((item) => (
=======
                    <div className="results-actions">
                        <button
                            type="button"
                            className="button button-secondary small-button"
                            onClick={fetchPlaylistHistory}
                        >
                            Refresh
                        </button>

                        <button
                            type="button"
                            className="button button-danger small-button"
                            onClick={clearPlaylistHistory}
                            disabled={!spotifyConnected}
                        >
                            Clear History
                        </button>
                    </div>
                </div>

                {historyError && <p className="error">{historyError}</p>}

                {visibleHistory.length === 0 ? (
                    <p className="empty-state">No playlists created yet.</p>
                ) : (
                    <ul className="history-list">
                        {visibleHistory.map((item) => (
>>>>>>> 0dd369d (bug fixes, backend improvements, database inconsistencies)
                            <li key={item.id}>
                                <strong>{item.playlistName}</strong>
                                <p>Seed artist: {item.seedArtist || "Unknown"}</p>
                                <p>Tracks: {item.trackCount}</p>
                                <p>Created: {new Date(item.createdAt).toLocaleString()}</p>
                                <a href={item.spotifyPlaylistUrl} target="_blank" rel="noreferrer">
                                    Open in Spotify
                                </a>
                            </li>
                        ))}
                    </ul>
                )}
            </form>
        </main>
    );
}

export default App;
