package rabbithole;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Local stand-in for the Spotify and Last.fm HTTP APIs, used by the integration tests. */
final class FakeMusicApis {

    private static final Pattern TRACK_QUERY = Pattern.compile("^(song \\d+) (.+)$");

    final Map<String, AtomicInteger> lastFmCalls = new ConcurrentHashMap<>();
    final List<String> libraryRemovals = new CopyOnWriteArrayList<>();
    final Set<String> revokedRefreshTokens = ConcurrentHashMap.newKeySet();

    volatile boolean failAddingTracks = false;
    volatile boolean rejectLastFmKey = false;

    private final AtomicInteger playlistCounter = new AtomicInteger();
    private final HttpServer server;

    FakeMusicApis() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/accounts/api/token", this::handleToken);
        server.createContext("/v1/me/playlists", this::handleCreatePlaylist);
        server.createContext("/v1/me/library", this::handleLibraryRemoval);
        server.createContext("/v1/me", this::handleMe);
        server.createContext("/v1/search", this::handleSearch);
        server.createContext("/v1/playlists", this::handleAddItems);
        server.createContext("/lastfm/2.0/", this::handleLastFm);
        server.start();
    }

    String url(String prefix) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + prefix;
    }

    int lastFmCallCount(String method) {
        AtomicInteger count = lastFmCalls.get(method);
        return count == null ? 0 : count.get();
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseQuery(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        if ("authorization_code".equals(form.get("grant_type"))) {
            String code = form.get("code");
            int expiresIn = code.startsWith("short") ? 1 : 3600;

            respond(exchange, 200, """
                    {"access_token":"access-%s","refresh_token":"refresh-%s","expires_in":%d}
                    """.formatted(code, code, expiresIn));
            return;
        }

        String refreshToken = form.get("refresh_token");

        if (revokedRefreshTokens.contains(refreshToken)) {
            respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }

        respond(exchange, 200, """
                {"access_token":"access-%s","expires_in":3600}
                """.formatted(refreshToken.replace("refresh-", "")));
    }

    private void handleMe(HttpExchange exchange) throws IOException {
        String accessToken = exchange.getRequestHeaders().getFirst("Authorization").replace("Bearer access-", "");

        respond(exchange, 200, "{\"id\":\"user-" + accessToken + "\",\"account_id\":\"account-" + accessToken + "\"}");
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

        if ("artist".equals(query.get("type"))) {
            respond(exchange, 200, """
                    {"artists":{"items":[{"id":"artist-1","name":"%s","images":[],"followers":{"total":1},"genres":[],"external_urls":{}}]}}
                    """.formatted(query.get("q")));
            return;
        }

        Matcher matcher = TRACK_QUERY.matcher(query.get("q"));

        if (!matcher.matches()) {
            respond(exchange, 200, "{\"tracks\":{\"items\":[]}}");
            return;
        }

        respond(exchange, 200, """
                {"tracks":{"items":[{"uri":"spotify:track:%s","name":"%s","artists":[{"name":"%s","id":"x"}]}]}}
                """.formatted(Integer.toHexString(query.get("q").hashCode()), matcher.group(1), matcher.group(2)));
    }

    private void handleCreatePlaylist(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();

        int id = playlistCounter.incrementAndGet();

        respond(exchange, 201, """
                {"id":"playlist%d","name":"Mix","external_urls":{"spotify":"https://open.spotify.com/playlist/playlist%d"}}
                """.formatted(id, id));
    }

    private void handleAddItems(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();

        if (failAddingTracks) {
            respond(exchange, 500, "{}");
            return;
        }

        respond(exchange, 201, "{\"snapshot_id\":\"s\"}");
    }

    private void handleLibraryRemoval(HttpExchange exchange) throws IOException {
        libraryRemovals.add(parseQuery(exchange.getRequestURI().getRawQuery()).get("uris"));
        respond(exchange, 200, "");
    }

    private void handleLastFm(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String method = query.get("method");

        lastFmCalls.computeIfAbsent(method, ignored -> new AtomicInteger()).incrementAndGet();

        if (rejectLastFmKey) {
            respond(exchange, 403, "{\"error\":10,\"message\":\"Invalid API key\"}");
            return;
        }

        switch (method) {
            case "artist.getsimilar" -> respond(exchange, 200, similarArtists(query.get("artist")));
            case "artist.getinfo" -> respond(exchange, 200, "{\"artist\":{\"stats\":{\"listeners\":\"1000\",\"playcount\":\"5000\"}}}");
            case "artist.gettoptracks" -> respond(exchange, 200, topTracks(query.get("artist"), Integer.parseInt(query.get("limit"))));
            default -> respond(exchange, 200, "{\"track\":{}}");
        }
    }

    private String similarArtists(String artist) {
        String artists = IntStream.rangeClosed(1, 3)
                .mapToObj(i -> "{\"name\":\"" + artist + " friend " + i + "\",\"match\":\"0.9\"}")
                .collect(Collectors.joining(","));

        return "{\"similarartists\":{\"artist\":[" + artists + "]}}";
    }

    private String topTracks(String artist, int limit) {
        String tracks = IntStream.rangeClosed(1, limit)
                .mapToObj(i -> """
                        {"name":"Song %d","listeners":"100","playcount":"500","artist":{"name":"%s"}}
                        """.formatted(i, artist))
                .collect(Collectors.joining(","));

        return "{\"toptracks\":{\"track\":[" + tracks + "]}}";
    }

    private Map<String, String> parseQuery(String raw) {
        Map<String, String> values = new HashMap<>();

        if (raw == null || raw.isBlank()) {
            return values;
        }

        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : ""
            );
        }

        return values;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);

        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }

        exchange.close();
    }
}
