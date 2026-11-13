# RabbitHole

[![CI](https://github.com/swobscha/rabbithole/actions/workflows/ci.yml/badge.svg)](https://github.com/swobscha/rabbithole/actions/workflows/ci.yml)

*One artist in. Hours of music out.*

**Try it live: [rabbithole.samwobschall.dev](https://rabbithole.samwobschall.dev)**

RabbitHole builds Spotify playlists of new music from an artist you choose. You pick a seed artist, choose how
popular or obscure and how similar or different the songs should be, and RabbitHole finds matching tracks and
creates the playlist on your Spotify account.

RabbitHole is not affiliated with or endorsed by Spotify or Last.fm.

## How it works

1. **Discover.** Last.fm supplies artists similar to your seed artist, plus each artist's top tracks and listener
   counts.
2. **Rank.** Every candidate track is scored using your settings: popularity, similarity to the seed, preferred
   artists and genres, and randomness. Songs you have already used can be skipped.
3. **Match.** The best-scoring tracks are looked up on Spotify, and only confident matches are kept.
4. **Create.** You can replace or remove tracks, then get the playlist into Spotify one of two ways.

### Two ways to use it

| | Without logging in | Connected to Spotify |
|---|---|---|
| Build, tweak and preview playlists | Yes | Yes |
| Get the playlist into Spotify | Copy the song links and paste them into a playlist in the Spotify desktop app | Created on your account in one click |
| Skip songs you've used before | No | Yes |
| Who can use it | Anyone | Up to 5 approved accounts, while the Spotify app is in development mode |

Logged-out searches use an app-only Spotify token (the client credentials flow), which can search the catalog but
can't read or change any account.

## Tech stack

| Part | Technology |
|---|---|
| Backend | Java 17, Spring Boot 4 (WebFlux), R2DBC |
| Database | H2 file database locally, PostgreSQL in production |
| Frontend | React 19, Vite |
| Music data | Spotify Web API, Last.fm API |
| Deployment | Docker (see `Dockerfile`) |

## Run it locally

### What you need

- JDK 17 or newer
- Node.js 20.19+ or 22.12+
- A Spotify developer app, from the [Spotify developer dashboard](https://developer.spotify.com/dashboard)
- A [Last.fm API key](https://www.last.fm/api/account/create)

### 1. Set up the Spotify app

In the Spotify developer dashboard, add this exact redirect URI to your app:

```
http://127.0.0.1:8080/api/spotify/callback
```

Spotify only accepts `127.0.0.1` for local redirect URIs, not `localhost`. The app takes care of switching hosts
during login. While the app is in Development Mode, add every account that will use it to the app's user
allowlist.

### 2. Add your keys

Copy `.env.example` to `.env.local` in the project root and fill in your Spotify and Last.fm keys. The backend reads
`.env.local` automatically when started from the project root. Real environment variables take priority over it.
`.env.local` is ignored by Git.

### 3. Start the backend

From the project root:

```bash
./mvnw spring-boot:run
```

In Windows PowerShell use `.\mvnw.cmd spring-boot:run`. If Maven can't find Java, set `JAVA_HOME` to your JDK first. The
backend runs on http://localhost:8080. You can also run `RabbitHoleApplication` from your IDE, as long as the working
directory is the project root.

### 4. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. The frontend talks to `http://localhost:8080` by default. To point it elsewhere, set
`VITE_API_BASE_URL` in `frontend/.env.local`.

## Tests

```bash
./mvnw test
```

The backend tests run against an in-memory database and a local fake of the Spotify and Last.fm APIs, so they need
no keys and never call the real services.

For the frontend:

```bash
cd frontend
npm run lint
npm run build
```

GitHub Actions runs both on every push and pull request (`.github/workflows/ci.yml`).

## Configuration

All settings are environment variables, or entries in `.env.local` for local development.

| Variable | Purpose | Default |
|---|---|---|
| `SPOTIFY_CLIENT_ID` | Spotify app client ID | required |
| `SPOTIFY_CLIENT_SECRET` | Spotify app client secret | required |
| `SPOTIFY_REDIRECT_URI` | Login callback; must match the Spotify dashboard exactly | `http://127.0.0.1:8080/api/spotify/callback` |
| `LASTFM_API_KEY` | Last.fm API key | required |
| `FRONTEND_URL` | Where users are sent after login | `http://localhost:5173` |
| `CORS_ALLOWED_ORIGINS` | Comma-separated frontend origins allowed to call the API | `http://localhost:5173,http://127.0.0.1:5173` |
| `SPRING_R2DBC_URL` | Database connection URL | local H2 file in `data/` |
| `SPRING_R2DBC_USERNAME` | Database user | `sa` |
| `SPRING_R2DBC_PASSWORD` | Database password | empty |
| `RATE_LIMIT_TRUSTED_PROXY_HOPS` | Number of reverse proxies in front of the app; `0` ignores `X-Forwarded-For` | `0` |
| `DEBUG_RANKINGS_ENABLED` | Writes ranking CSV files to `debug-rankings/` | `false` |
| `SENTRY_DSN` | Turns on error alerts through [Sentry](https://sentry.io) | empty (off) |
| `LOGGING_LEVEL_RABBITHOLE` | Log detail; `DEBUG` shows every Spotify match decision | `INFO` |
| `VITE_API_BASE_URL` | Frontend only: backend URL used by the built frontend | `http://localhost:8080` when run locally |

The database tables are created or updated automatically at startup from `src/main/resources/schema.sql`.
Idle database connections close after 2 minutes so a scale-to-zero database can sleep.

### Logs and error alerts

The backend writes logs to the console, which your host keeps. Each generation logs one line when it starts and one
when it finishes. To get alerts, create a free Sentry project and set `SENTRY_DSN`: Sentry then emails you when an
unexpected error happens. IP addresses and request headers are not sent to Sentry.

## Deployment

The `Dockerfile` builds the backend and starts it, as an unprivileged user, on the port in the `PORT` environment
variable (default `10000`). Set the variables from the table above on your host. In production:

- Set `SPOTIFY_REDIRECT_URI` to `https://<your-backend-host>/api/spotify/callback` and add the same URI in the
  Spotify dashboard.
- Set `FRONTEND_URL` and `CORS_ALLOWED_ORIGINS` to your frontend's address.
- Point `SPRING_R2DBC_URL` at PostgreSQL, for example `r2dbc:postgresql://<host>/<database>?sslMode=require`.

Build the frontend with `npm run build` in `frontend/` and host the `frontend/dist` folder as a static site. If
`VITE_API_BASE_URL` isn't set at build time, a non-local frontend uses the production backend address written in
`frontend/src/App.jsx`.

On a free hosting plan that sleeps when idle, a request to `/ping` every 5 to 10 minutes keeps the backend awake.

## API

| Method | Path | Login needed | Purpose |
|---|---|---|---|
| `GET` | `/ping` | no | Health check |
| `GET` | `/api/spotify/login` | no | Starts Spotify login |
| `GET` | `/api/spotify/callback` | no | Spotify login callback |
| `GET` | `/api/spotify/status` | no | Whether the current session is connected |
| `POST` | `/api/spotify/logout` | yes | Disconnects and deletes the account's data |
| `GET` | `/api/spotify/artists/search?query=` | optional | Finds Spotify artists for the seed |
| `POST` | `/api/playlists/generate` | optional | Generates a playlist; skipping used songs needs a login |
| `POST` | `/api/playlists/replace-track` | optional | Finds a fresh replacement for one track |
| `POST` | `/api/playlists/create` | yes | Creates the playlist on Spotify |
| `GET` | `/api/playlists/history` | yes | Lists playlists you created |
| `DELETE` | `/api/playlists/history` | yes | Clears your history |

Requests that need a login send `Authorization: Bearer <session token>`. The frontend receives that token after
Spotify login and keeps it in the browser's local storage. `test-generate.http` has an example request.

## Privacy and data

Each Spotify account's history is kept separately, keyed by Spotify's stable `account_id`. Data saved under the
older Spotify user id is moved to the `account_id` the next time that person logs in. Spotify refresh tokens are
stored encrypted with a key derived from the browser's session token, which is never stored on the server.

**Disconnect** deletes the account's history and signs it out on every device. If someone removes RabbitHole in
their Spotify account settings instead, their data is deleted the next time RabbitHole tries to use that access.
The privacy policy is at `frontend/public/privacy.html`.

## Limits

- **Spotify Development Mode** allows up to 5 allowlisted users, and the app owner needs Spotify Premium. Spotify
  only grants wider access to registered organizations with a large existing user base.
- **Last.fm** data may only be used for non-commercial purposes without a separate agreement.
- **Rate limits:** each account can generate 5 playlists, replace 15 tracks and create 10 playlists per minute.
