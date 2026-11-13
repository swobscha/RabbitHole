import { useEffect, useState } from "react";
import "./App.css";
const LOCAL_API_BASE_URL = "http://localhost:8080";
const PRODUCTION_API_BASE_URL = "https://rabbithole-api.samwobschall.dev";

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

const SESSION_STORAGE_KEY = "rabbitholeSession";
// The key used before the app was renamed. Read once so people stay signed in after the rename.
const LEGACY_SESSION_STORAGE_KEY = "spotifyBotSession";
let sessionTokenInMemory = "";

function readSessionToken() {
    try {
        const token = window.localStorage.getItem(SESSION_STORAGE_KEY);

        if (token) {
            return token;
        }

        const legacyToken = window.localStorage.getItem(LEGACY_SESSION_STORAGE_KEY);

        if (legacyToken) {
            writeSessionToken(legacyToken);
            return legacyToken;
        }

        return sessionTokenInMemory;
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

        window.localStorage.removeItem(LEGACY_SESSION_STORAGE_KEY);
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

// Slider positions before a preset is picked, and again after a preset is switched off.
// Popularity runs from 0 (most obscure) to 1 (most popular).
const DEFAULT_SETTINGS = { popularity: 0.5, similarityScale: 0.5, randomness: 0.7 };

const PRESETS = [
    {
        name: "Deep Cuts",
        description: "Obscure songs, balanced distance",
        popularity: 0.1,
        similarityScale: 0.5,
        randomness: 0.35,
    },
    {
        name: "Nearby Underground",
        description: "Obscure songs close to seed",
        popularity: 0.1,
        similarityScale: 0.8,
        randomness: 0.3,
    },
    {
        name: "Popular Similar",
        description: "Bigger songs close to seed",
        popularity: 0.85,
        similarityScale: 0.85,
        randomness: 0.2,
    },
    {
        name: "Far Discovery",
        description: "Obscure songs farther away",
        popularity: 0.2,
        similarityScale: 0.15,
        randomness: 0.45,
    },
    {
        name: "Chaos Mode",
        description: "Balanced streams, high randomness",
        popularity: 0.5,
        similarityScale: 0.5,
        randomness: 1,
    },
];

// Shown while a playlist generates, roughly following the server's steps. The timings are estimates:
// the server doesn't report progress, and an artist loaded ahead of time skips the early steps.
const GENERATION_STAGES = [
    { after: 0, message: "Finding artists similar to your seed…" },
    { after: 3, message: "Collecting their top songs…" },
    { after: 6, message: "Ranking songs for your settings…" },
    { after: 9, message: "Matching songs on Spotify…" },
    { after: 18, message: "Still working. The first playlist for a new artist takes the longest." },
];

function StepHeading({ eyebrow, title, description, badge }) {
    return (
        <div className="step-heading">
            <div>
                <span className="eyebrow">{eyebrow}</span>
                <h2>{title}</h2>
                {description && <p className="step-description">{description}</p>}
            </div>

            {badge && <span className="step-complete">{badge}</span>}
        </div>
    );
}

function BackButton({ onClick }) {
    return (
        <button type="button" className="button button-back" onClick={onClick}>
            <span aria-hidden="true">← </span>Back
        </button>
    );
}

function App() {
    const [seedArtist, setSeedArtist] = useState("");
    const [playlistName, setPlaylistName] = useState("RabbitHole Mix");
    const [playlistSize, setPlaylistSize] = useState(20);
    const [restrictedArtists, setRestrictedArtists] = useState("");
    const [preferredArtists, setPreferredArtists] = useState("");
    const [preferredGenres, setPreferredGenres] = useState("");

    const [randomness, setRandomness] = useState(DEFAULT_SETTINGS.randomness);
    const [avoidUsedTracks, setAvoidUsedTracks] = useState(true);
    const [popularity, setPopularity] = useState(DEFAULT_SETTINGS.popularity);
    const [similarityScale, setSimilarityScale] = useState(DEFAULT_SETTINGS.similarityScale);
    const [advancedOpen, setAdvancedOpen] = useState(false);

    const [spotifyConnected, setSpotifyConnected] = useState(false);
    const [loginError, setLoginError] = useState(initialLoginError);
    const [wizardStage, setUiStage] = useState(2);
    const uiStage = wizardStage;

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
    const [generationStartedAt, setGenerationStartedAt] = useState(0);
    const [clock, setClock] = useState(0);
    const [creating, setCreating] = useState(false);
    const [replacingTrackIndex, setReplacingTrackIndex] = useState(null);
    const [previewTrackUri, setPreviewTrackUri] = useState(null);

    const [error, setError] = useState("");
    const [createError, setCreateError] = useState("");
    const [replaceError, setReplaceError] = useState("");
    const [historyError, setHistoryError] = useState("");
    const [generationNotice, setGenerationNotice] = useState("");
    const [copyMessage, setCopyMessage] = useState("");
    const [copyFallback, setCopyFallback] = useState("");

    const hiddenNicheArtistScale = 0;

    // A preset shows as on while the sliders still match it, so moving a slider switches it off.
    const activePreset = PRESETS.find((preset) =>
        Number(popularity) === preset.popularity
        && Number(similarityScale) === preset.similarityScale
        && Number(randomness) === preset.randomness
    );

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

        // Only someone who was logged in can lose a connection; logged-out features never return 401.
        if (response.status === 401 && token) {
            writeSessionToken("");
            setSpotifyConnected(false);
            setLoginError("Your Spotify connection ended. Connect again to continue.");
        }

        return response;
    }

    function backendOfflineMessage() {
        return "Could not reach the RabbitHole server. Please try again in a moment.";
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


    function formatSliderValue(value) {
        const number = Number(value);

        if (!Number.isFinite(number)) {
            return "0.0";
        }

        return number.toFixed(1);
    }

    function formatPopularity(value) {
        const number = Number(value);

        if (number <= 0.2) {
            return `${formatSliderValue(value)} — Much more obscure`;
        }

        if (number < 0.5) {
            return `${formatSliderValue(value)} — More obscure`;
        }

        if (number === 0.5) {
            return `${formatSliderValue(value)} — Balanced`;
        }

        if (number < 0.8) {
            return `${formatSliderValue(value)} — More popular`;
        }

        return `${formatSliderValue(value)} — Much more popular`;
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

    function spotifyTrackId(track) {
        if (!track?.spotifyUri) {
            return "";
        }

        const parts = track.spotifyUri.split(":");

        return parts[parts.length - 1] || "";
    }

    function spotifyTrackUrl(track) {
        const trackId = spotifyTrackId(track);

        return trackId ? `https://open.spotify.com/track/${trackId}` : "";
    }

    // Spotify's embedded player: a short preview, or the full song for visitors logged into Spotify.
    function spotifyEmbedUrl(track) {
        const trackId = spotifyTrackId(track);

        return trackId ? `https://open.spotify.com/embed/track/${trackId}?utm_source=generator&theme=0` : "";
    }

    function buildGenerationRequest() {
        return {
            // The artist picked in step 2, not the search text, which can name a different artist.
            seedArtist: selectedSeedArtist?.name || seedArtist,
            seedSpotifyArtistId: selectedSeedArtist?.id || "",
            playlistName,
            playlistSize: Number(playlistSize),
            restrictedArtists: splitInput(restrictedArtists),
            preferredArtists: splitInput(preferredArtists),
            preferredGenres: splitInput(preferredGenres),
            randomness: Number(randomness),
            generationSeed: createGenerationSeed(),
            avoidUsedTracks: spotifyConnected && avoidUsedTracks,
            nicheArtistScale: hiddenNicheArtistScale,
            // The backend's scale runs the other way: 1 asks for the most obscure songs.
            nicheSongScale: Number((1 - Number(popularity)).toFixed(2)),
            similarityScale: Number(similarityScale),
        };
    }

    // Clicking the preset that is already on switches it off and returns the sliders to their defaults.
    function togglePreset(preset) {
        const settings = activePreset === preset ? DEFAULT_SETTINGS : preset;

        setPopularity(settings.popularity);
        setSimilarityScale(settings.similarityScale);
        setRandomness(settings.randomness);
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
        setUiStage(2);
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

    // selectTopMatch picks the best match straight away (used by Enter); the other matches stay
    // listed in step 2, so a wrong pick is one click to fix.
    async function searchSeedArtists({ selectTopMatch = false } = {}) {
        if (!seedArtist.trim()) {
            setArtistSearchError("Enter a seed artist first.");
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
            const options = Array.isArray(data) ? data.slice(0, 5) : [];

            setSeedArtistOptions(options);

            if (options.length > 0 && selectTopMatch) {
                selectSeedArtist(options[0]);
            } else if (options.length > 0) {
                setUiStage(3);
            } else {
                setArtistSearchError("No matching Spotify artists found.");
            }
        } catch (err) {
            setArtistSearchError(messageFromError(err, "Could not search Spotify artists."));
        } finally {
            setArtistSearchLoading(false);
        }
    }

    // Enter in the artist box searches and picks the best match, instead of submitting the whole form.
    function handleSeedArtistKeyDown(event) {
        if (event.key !== "Enter" || event.nativeEvent.isComposing) {
            return;
        }

        event.preventDefault();

        if (!artistSearchLoading) {
            searchSeedArtists({ selectTopMatch: true });
        }
    }

    function connectSpotify() {
        setLoginError("");
        window.location.href = apiUrl("/api/spotify/login");
    }

    async function disconnectSpotify() {
        const confirmed = window.confirm(
            "Disconnect Spotify? This also deletes your RabbitHole playlist history. Playlists already on Spotify are not affected."
        );

        if (!confirmed) {
            return;
        }

        try {
            const response = await apiFetch("/api/spotify/logout", { method: "POST" });

            if (!response.ok && response.status !== 401) {
                const message = await readApiError(response, "Could not disconnect Spotify.");
                setLoginError(`${message} Your data was not deleted; try again.`);
                return;
            }
        } catch (err) {
            setLoginError(`${messageFromError(err, "Could not disconnect Spotify.")} Your data was not deleted; try again.`);
            return;
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

    // Ticks twice a second while generating, to move the progress message along.
    useEffect(() => {
        if (!loading) {
            return undefined;
        }

        const timer = setInterval(() => setClock(Date.now()), 500);

        return () => clearInterval(timer);
    }, [loading]);

    const generationSeconds = Math.max(0, (clock - generationStartedAt) / 1000);
    const generationMessage = GENERATION_STAGES.findLast((stage) => generationSeconds >= stage.after).message;

    async function generatePlaylist(event) {
        event.preventDefault();

        if (!selectedSeedArtist) {
            setError("Search and select the correct Spotify seed artist before generating.");
            return;
        }

        setLoading(true);
        setGenerationStartedAt(Date.now());
        setError("");
        setCreateError("");
        setReplaceError("");
        setGenerationNotice("");
        resetCopy();
        setPlaylist(null);
        setCreatedPlaylist(null);
        setPreviewTrackUri(null);

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
            resetCopy();
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
        resetCopy();
    }

    function clearPlaylist() {
        setPlaylist(null);
        setCreatedPlaylist(null);
        setCreateError("");
        setReplaceError("");
        setGenerationNotice("");
        resetCopy();
    }

    function resetCopy() {
        setCopyMessage("");
        setCopyFallback("");
    }

    // Works without a login: Spotify's desktop app accepts pasted track links into a playlist.
    async function copySpotifyLinks() {
        const links = playlist.tracks.map((item) => spotifyTrackUrl(item.track)).filter(Boolean);

        if (links.length === 0) {
            setCopyMessage("These tracks have no Spotify links to copy.");
            return;
        }

        const text = links.join("\n");

        if (await copyText(text)) {
            setCopyFallback("");
            setCopyMessage(
                `Copied ${links.length} links. In the Spotify desktop app, open a playlist and press Ctrl+V (⌘V on a Mac).`
            );
        } else {
            setCopyFallback(text);
            setCopyMessage("Your browser blocked copying. Select the links below, copy them, and paste them into a Spotify playlist.");
        }
    }

    // The Clipboard API can be blocked (for example inside embedded browsers), so fall back to the older copy command.
    async function copyText(text) {
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch {
            const scratch = document.createElement("textarea");
            scratch.value = text;
            scratch.setAttribute("readonly", "");
            scratch.style.position = "fixed";
            scratch.style.opacity = "0";
            document.body.appendChild(scratch);
            scratch.select();

            try {
                return document.execCommand("copy");
            } catch {
                return false;
            } finally {
                scratch.remove();
            }
        }
    }

    function goBack() {
        setUiStage((current) => Math.max(2, current - 1));
    }

    // Starts loading the artist's data on the server while the visitor finishes the other steps,
    // so generating is quicker. Nothing waits for it, and a failure only means no head start.
    function prefetchSeedArtist(name) {
        if (!name) {
            return;
        }

        fetch(apiUrl("/api/playlists/prefetch"), {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ seedArtist: name }),
        }).catch(() => {});
    }

    function selectSeedArtist(artist) {
        prefetchSeedArtist(artist.name);
        setSelectedSeedArtist(artist);
        setArtistSearchError("");
        setCreatedPlaylist(null);
        setUiStage(4);
    }

    function stageClass(stage) {
        if (uiStage === stage) {
            return "wizard-step active-step";
        }

        if (uiStage > stage) {
            return "wizard-step completed-step";
        }

        return "wizard-step";
    }

    // Read out by screen readers while something is loading, and once a playlist is ready.
    let statusMessage = "";

    if (artistSearchLoading) {
        statusMessage = "Searching for artists…";
    } else if (loading) {
        statusMessage = "Generating your playlist…";
    } else if (creating) {
        statusMessage = "Creating the playlist on Spotify…";
    } else if (replacingTrackIndex !== null) {
        statusMessage = "Replacing the track…";
    } else if (playlist) {
        statusMessage = `Playlist ready with ${playlist.tracks.length} tracks.`;
    }

    return (
        <main className="page-shell">
            <header className="app-header">
                <div>
                    <p className="eyebrow">Discovery engine</p>
                    <h1>RabbitHole</h1>
                    <p className="header-copy">
                        One artist in. Hours of music out.
                    </p>
                </div>
            </header>

            <div className="wizard">
                <section className="wizard-step">
                    <StepHeading
                        eyebrow="Optional"
                        title="Connect Spotify"
                        badge={spotifyConnected && "Connected"}
                    />

                    <ul className="mode-compare">
                        <li>
                            <strong>Without connecting:</strong> build a playlist, then copy the song links and
                            paste them into a playlist in the Spotify desktop app.
                        </li>
                        <li>
                            <strong>Connected:</strong> RabbitHole creates the playlist on your account in one
                            click and skips songs you've used before. Approved accounts only for now.
                        </li>
                    </ul>

                    {spotifyConnected ? (
                        <button
                            type="button"
                            className="button button-secondary button-small disconnect-button"
                            onClick={disconnectSpotify}
                        >
                            Disconnect
                        </button>
                    ) : (
                        <button
                            type="button"
                            className="button spotify-button"
                            onClick={connectSpotify}
                        >
                            <span className="spotify-button-text">Connect Spotify</span>
                            <span className="spotify-hover-icon" aria-hidden="true">♪</span>
                        </button>
                    )}

                    {loginError && <p className="error" role="alert">{loginError}</p>}
                </section>

                {uiStage >= 2 && (
                    <form className="wizard-form" onSubmit={generatePlaylist}>
                        <section className={stageClass(2)}>
                            <StepHeading
                                eyebrow="Step 01"
                                title="Find your seed artist"
                                description="Search Spotify so the generator knows exactly which artist you mean."
                                badge={uiStage > 2 && (seedArtist || "Artist entered")}
                            />

                            <div className="field">
                                <label htmlFor="seedArtist">Seed Artist</label>
                                <input
                                    id="seedArtist"
                                    value={seedArtist}
                                    onChange={handleSeedArtistChange}
                                    onKeyDown={handleSeedArtistKeyDown}
                                    placeholder="Daft Punk"
                                />
                            </div>

                            <div className="step-actions">
                                <button
                                    type="button"
                                    className="button button-primary search-button"
                                    onClick={() => searchSeedArtists()}
                                    disabled={artistSearchLoading}
                                >
                                    {artistSearchLoading ? "Searching..." : "Find Matching Artists"}
                                </button>
                            </div>

                            {artistSearchError && (
                                <p className="error" role="alert">{artistSearchError}</p>
                            )}
                        </section>

                        {uiStage >= 3 && (
                            <section className={stageClass(3)}>
                                <StepHeading
                                    eyebrow="Step 02"
                                    title="Choose the right artist"
                                    description="Pick one of the five Spotify matches. The selected artist becomes the seed."
                                    badge={uiStage > 3 && selectedSeedArtist?.name}
                                />

                                <div className="artist-options">
                                    {seedArtistOptions.slice(0, 5).map((artist) => (
                                        <div key={artist.id} className="artist-choice">
                                            <button
                                                type="button"
                                                className={`artist-option ${
                                                    selectedSeedArtist?.id === artist.id ? "selected" : ""
                                                }`}
                                                aria-pressed={selectedSeedArtist?.id === artist.id}
                                                onClick={() => selectSeedArtist(artist)}
                                            >
                                                {artist.imageUrl ? (
                                                    <img
                                                        className="artist-image"
                                                        src={artist.imageUrl}
                                                        alt=""
                                                    />
                                                ) : (
                                                    <span className="artist-placeholder" aria-hidden="true">
                                                        {artist.name?.charAt(0)?.toUpperCase() || "?"}
                                                    </span>
                                                )}
    
                                                <span className="artist-name">{artist.name}</span>
                                                {Number(artist.followers) > 0 && (
                                                    <span className="artist-meta">
                                                        {formatNumber(artist.followers)} followers
                                                    </span>
                                                )}
    
                                                {artist.genres?.length > 0 && (
                                                    <span className="artist-genres">
                                                        {artist.genres.slice(0, 2).join(" · ")}
                                                    </span>
                                                )}
                                            </button>
    
                                            {artist.spotifyUrl && (
                                                <a
                                                    className="text-link artist-spotify-link"
                                                    href={artist.spotifyUrl}
                                                    target="_blank"
                                                    rel="noreferrer"
                                                    aria-label={`View on Spotify: ${artist.name}`}
                                                >
                                                    View on Spotify ↗
                                                </a>
                                            )}
                                        </div>
                                    ))}
                                </div>

                                {uiStage === 3 && (
                                    <div className="step-actions">
                                        <BackButton onClick={goBack} />

                                        <button
                                            type="button"
                                            className="button button-secondary"
                                            onClick={() => searchSeedArtists()}
                                            disabled={artistSearchLoading}
                                        >
                                            {artistSearchLoading ? "Searching..." : "Search Again"}
                                        </button>
                                    </div>
                                )}
                            </section>
                        )}

                        {uiStage >= 4 && (
                            <section className={stageClass(4)}>
                                <StepHeading
                                    eyebrow="Step 03"
                                    title="Name and size"
                                    description="Give the playlist a name and choose how many songs you want."
                                    badge={uiStage > 4 && `${playlistSize} tracks`}
                                />

                                {selectedSeedArtist && (
                                    <div className="seed-artist">
                                        {/* Spotify's API has no artist banners, so the photo is cropped into one. */}
                                        <div className="seed-artist-banner" aria-hidden="true">
                                            {selectedSeedArtist.imageUrl ? (
                                                <img src={selectedSeedArtist.imageUrl} alt="" />
                                            ) : (
                                                <span className="seed-artist-initial">
                                                    {selectedSeedArtist.name?.charAt(0)?.toUpperCase() || "?"}
                                                </span>
                                            )}
                                        </div>

                                        <p className="seed-artist-label">Seed artist</p>
                                        <p className="seed-artist-name">{selectedSeedArtist.name}</p>

                                        {selectedSeedArtist.spotifyUrl && (
                                            <a
                                                className="text-link seed-artist-link"
                                                href={selectedSeedArtist.spotifyUrl}
                                                target="_blank"
                                                rel="noreferrer"
                                                aria-label={`View on Spotify: ${selectedSeedArtist.name}`}
                                            >
                                                View on Spotify ↗
                                            </a>
                                        )}
                                    </div>
                                )}

                                <div className="field">
                                    <label htmlFor="playlistName">Playlist Name</label>
                                    <input
                                        id="playlistName"
                                        value={playlistName}
                                        onChange={(event) => setPlaylistName(event.target.value)}
                                        placeholder="Late Night Discovery"
                                    />
                                </div>

                                <div className="field">
                                    <label htmlFor="playlistSize" className="range-label">
                                        <span>Playlist Size</span>
                                        <span className="range-value">{playlistSize}</span>
                                    </label>

                                    <input
                                        id="playlistSize"
                                        type="range"
                                        min="1"
                                        max="50"
                                        step="1"
                                        value={playlistSize}
                                        onChange={(event) => setPlaylistSize(event.target.value)}
                                    />

                                    <div className="range-ends">
                                        <span>1</span>
                                        <span>50</span>
                                    </div>
                                </div>

                                {uiStage === 4 && (
                                    <div className="step-actions">
                                        <BackButton onClick={goBack} />

                                        <button
                                            type="button"
                                            className="button button-primary"
                                            onClick={() => setUiStage(5)}
                                            disabled={!playlistName.trim() || !selectedSeedArtist}
                                        >
                                            Continue<span aria-hidden="true"> →</span>
                                        </button>
                                    </div>
                                )}
                            </section>
                        )}

                        {uiStage >= 5 && (
                            <section className={stageClass(5)}>
                                <StepHeading
                                    eyebrow="Step 04"
                                    title="Shape the discovery"
                                    description="Pick a preset, optionally fine-tune it, then generate."
                                />

                                <div className="preset-grid">
                                    {PRESETS.map((preset) => (
                                        <button
                                            key={preset.name}
                                            type="button"
                                            className={`preset-button ${activePreset === preset ? "selected" : ""}`}
                                            aria-pressed={activePreset === preset}
                                            onClick={() => togglePreset(preset)}
                                        >
                                            <strong>{preset.name}</strong>
                                            <span>{preset.description}</span>
                                        </button>
                                    ))}
                                </div>

                                {spotifyConnected && (
                                    <label className="checkbox-row">
                                        <input
                                            type="checkbox"
                                            checked={avoidUsedTracks}
                                            onChange={(event) => setAvoidUsedTracks(event.target.checked)}
                                        />
                                        <span>Skip songs I've already used</span>
                                    </label>
                                )}

                                <button
                                    type="button"
                                    className="button button-secondary advanced-toggle"
                                    aria-expanded={advancedOpen}
                                    onClick={() => setAdvancedOpen((current) => !current)}
                                >
                                    {advancedOpen ? "Hide Advanced Settings" : "Show Advanced Settings"}
                                </button>

                                {advancedOpen && (
                                    <div className="advanced-settings">
                                        <div className="field">
                                            <label htmlFor="restrictedArtists">Restricted Artists</label>
                                            <input
                                                id="restrictedArtists"
                                                value={restrictedArtists}
                                                onChange={(event) => setRestrictedArtists(event.target.value)}
                                                placeholder="Coldplay, Imagine Dragons"
                                            />
                                        </div>

                                        <div className="field">
                                            <label htmlFor="preferredArtists">Preferred Artists</label>
                                            <input
                                                id="preferredArtists"
                                                value={preferredArtists}
                                                onChange={(event) => setPreferredArtists(event.target.value)}
                                                placeholder="Justice, Kavinsky"
                                            />
                                        </div>

                                        <div className="field">
                                            <label htmlFor="preferredGenres">Preferred Genres / Tags</label>
                                            <input
                                                id="preferredGenres"
                                                value={preferredGenres}
                                                onChange={(event) => setPreferredGenres(event.target.value)}
                                                placeholder="electronic, dance, house"
                                            />
                                        </div>

                                        <div className="field slider-field">
                                            <label htmlFor="randomness">
                                                Randomness
                                                <span>{formatSliderValue(randomness)}</span>
                                            </label>
                                            <input
                                                id="randomness"
                                                type="range"
                                                min="0"
                                                max="1"
                                                step="0.1"
                                                value={randomness}
                                                onChange={(event) => setRandomness(event.target.value)}
                                            />
                                        </div>

                                        <div className="field slider-field">
                                            <label htmlFor="popularity">
                                                Popularity
                                                <span>{formatPopularity(popularity)}</span>
                                            </label>
                                            <input
                                                id="popularity"
                                                type="range"
                                                min="0"
                                                max="1"
                                                step="0.1"
                                                value={popularity}
                                                onChange={(event) => setPopularity(event.target.value)}
                                            />
                                        </div>

                                        <div className="field slider-field">
                                            <label htmlFor="similarity">
                                                Similarity to Seed
                                                <span>{formatSimilarityScale(similarityScale)}</span>
                                            </label>
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
                                    </div>
                                )}

                                <div className="step-actions final-actions">
                                    <BackButton onClick={goBack} />

                                    <button
                                        type="submit"
                                        className="button button-primary generate-button"
                                        disabled={loading || !selectedSeedArtist}
                                    >
                                        {loading ? "Generating..." : "Generate Playlist"}
                                    </button>
                                </div>

                                {loading && (
                                    <div className="generation-progress">
                                        <div className="progress-track" aria-hidden="true">
                                            <span />
                                        </div>
                                        <p>{generationMessage}</p>
                                    </div>
                                )}

                                {error && <p className="error" role="alert">{error}</p>}
                            </section>
                        )}
                    </form>
                )}
            </div>

            {playlist && (
                <section className="results-panel" id="playlist-results">
                    <div className="results-header">
                        <div>
                            <p className="eyebrow">Generated playlist</p>
                            <h2>{playlist.playlistName}</h2>
                            <p>
                                {playlist.tracks.length} tracks · Seeded by {playlist.seedArtist}
                            </p>
                        </div>

                        <div className="results-actions">
                            <button
                                type="button"
                                className={`button ${spotifyConnected ? "button-secondary" : "button-primary"}`}
                                onClick={copySpotifyLinks}
                                disabled={playlist.tracks.length === 0}
                            >
                                Copy Spotify links
                            </button>

                            {spotifyConnected && (
                                <button
                                    type="button"
                                    className="button button-primary"
                                    onClick={createPlaylist}
                                    disabled={creating || playlist.tracks.length === 0}
                                >
                                    {creating ? "Creating..." : "Create on Spotify"}
                                </button>
                            )}

                            <button
                                type="button"
                                className="button button-secondary"
                                onClick={clearPlaylist}
                            >
                                Clear
                            </button>
                        </div>
                    </div>

                    {generationNotice && <p className="notice" role="status">{generationNotice}</p>}
                    {copyMessage && <p className="notice" role="status">{copyMessage}</p>}
                    {copyFallback && (
                        <textarea
                            className="copy-fallback"
                            readOnly
                            rows={6}
                            value={copyFallback}
                            onFocus={(event) => event.target.select()}
                        />
                    )}
                    {createError && <p className="error" role="alert">{createError}</p>}
                    {replaceError && <p className="error" role="alert">{replaceError}</p>}

                    {createdPlaylist && (
                        <div className="success" role="status">
                            <strong>{createdPlaylist.status}</strong>
                            <span>{createdPlaylist.playlistName}</span>
                            <span>{createdPlaylist.trackCount} tracks added</span>
                            <a
                                href={createdPlaylist.spotifyPlaylistUrl}
                                target="_blank"
                                rel="noreferrer"
                            >
                                Open Spotify Playlist<span aria-hidden="true"> ↗</span>
                            </a>
                        </div>
                    )}

                    <ol className="track-list">
                        {playlist.tracks.map((item, index) => {
                            const spotifyUrl = spotifyTrackUrl(item.track);
                            const embedUrl = spotifyEmbedUrl(item.track);
                            const previewOpen = Boolean(embedUrl) && previewTrackUri === item.track.spotifyUri;

                            return (
                                <li
                                    key={`${item.track.spotifyUri || item.track.id || item.track.name}-${index}`}
                                    className="track-card"
                                >
                                    <div className="track-topline">
                                        <span className="track-number">
                                            {String(index + 1).padStart(2, "0")}
                                        </span>

                                        <div className="track-title">
                                            <h3>{item.track.name}</h3>
                                            <p>{item.track.artistName}</p>
                                        </div>
                                    </div>

                                    {(item.track.genres || []).length > 0 && (
                                        <div className="genre-row">
                                            {item.track.genres.slice(0, 5).map((genre) => (
                                                <span key={genre}>{genre}</span>
                                            ))}
                                        </div>
                                    )}

                                    {previewOpen && (
                                        <iframe
                                            className="track-preview"
                                            src={embedUrl}
                                            title={`Spotify player: ${item.track.name} by ${item.track.artistName}`}
                                            allow="autoplay; clipboard-write; encrypted-media; fullscreen; picture-in-picture"
                                            loading="lazy"
                                        />
                                    )}

                                    <div className="track-actions">
                                        {spotifyUrl && (
                                            <a
                                                className="text-link"
                                                href={spotifyUrl}
                                                target="_blank"
                                                rel="noreferrer"
                                                aria-label={`Open in Spotify: ${item.track.name}`}
                                            >
                                                Open in Spotify ↗
                                            </a>
                                        )}

                                        {embedUrl && (
                                            <button
                                                type="button"
                                                className="button button-secondary button-small"
                                                aria-expanded={previewOpen}
                                                aria-label={`${previewOpen ? "Hide preview" : "Preview"}: ${item.track.name}`}
                                                onClick={() => setPreviewTrackUri(previewOpen ? null : item.track.spotifyUri)}
                                            >
                                                {previewOpen ? "Hide preview" : (
                                                    <>
                                                        <span aria-hidden="true">▶ </span>Preview
                                                    </>
                                                )}
                                            </button>
                                        )}

                                        <button
                                            type="button"
                                            className="button button-secondary button-small"
                                            aria-label={`${replacingTrackIndex === index ? "Replacing" : "Replace"} ${item.track.name}`}
                                            onClick={() => replaceTrack(index)}
                                            disabled={replacingTrackIndex !== null}
                                        >
                                            {replacingTrackIndex === index ? "Replacing..." : "Replace"}
                                        </button>

                                        <button
                                            type="button"
                                            className="button button-danger button-small"
                                            aria-label={`Remove ${item.track.name}`}
                                            onClick={() =>
                                                removeTrack(
                                                    item.track.id,
                                                    item.track.spotifyUri,
                                                    index
                                                )
                                            }
                                        >
                                            Remove
                                        </button>
                                    </div>
                                </li>
                            );
                        })}
                    </ol>
                </section>
            )}

            {spotifyConnected && (
                <section className="history-panel">
                    <div className="results-header">
                        <div>
                            <p className="eyebrow">History</p>
                            <h2>Playlist History</h2>
                        </div>

                        <div className="results-actions">
                            <button
                                type="button"
                                className="button button-secondary button-small"
                                onClick={fetchPlaylistHistory}
                            >
                                Refresh
                            </button>

                            <button
                                type="button"
                                className="button button-danger button-small"
                                onClick={clearPlaylistHistory}
                            >
                                Clear History
                            </button>
                        </div>
                    </div>

                    {historyError && <p className="error" role="alert">{historyError}</p>}

                    {visibleHistory.length === 0 ? (
                        <p className="empty-state">No playlists created yet.</p>
                    ) : (
                        <ul className="history-list">
                            {visibleHistory.map((item) => (
                                <li key={item.id}>
                                    <div>
                                        <strong>{item.playlistName}</strong>
                                        <span>
                                            {item.trackCount} tracks · {item.seedArtist || "Unknown seed"}
                                        </span>
                                    </div>

                                    <div>
                                        <span>{new Date(item.createdAt).toLocaleString()}</span>
                                        <a
                                            className="text-link"
                                            href={item.spotifyPlaylistUrl}
                                            target="_blank"
                                            rel="noreferrer"
                                            aria-label={`Open ${item.playlistName} on Spotify`}
                                        >
                                            Open ↗
                                        </a>
                                    </div>
                                </li>
                            ))}
                        </ul>
                    )}
                </section>
            )}

            <footer className="app-footer">
                <p>
                    Playlists and music data from{" "}
                    <a href="https://www.spotify.com" target="_blank" rel="noreferrer">Spotify</a>.
                    Listening stats powered by AudioScrobbler from{" "}
                    <a href="https://www.last.fm" target="_blank" rel="noreferrer">Last.fm</a>.
                </p>
                <p>
                    <a href="/privacy.html">Privacy policy</a>
                    {" · "}
                    RabbitHole is not affiliated with or endorsed by Spotify or Last.fm.
                </p>
                <p>
                    Built by{" "}
                    <a href="https://samwobschall.dev" target="_blank" rel="noopener">samwobschall.dev</a>
                </p>
            </footer>

            <p className="visually-hidden" role="status">{statusMessage}</p>
        </main>
    );
}

export default App;
