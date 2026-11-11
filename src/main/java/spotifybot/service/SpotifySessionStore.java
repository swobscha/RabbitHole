package spotifybot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import spotifybot.model.SpotifySession;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Keeps Spotify logins across restarts. The session token itself is never stored: rows are
 * looked up by a hash of it, and the refresh token is encrypted with a key derived from it, so
 * the database alone cannot be used to recover a Spotify credential.
 */
@Service
public class SpotifySessionStore {

    private static final Logger log = LoggerFactory.getLogger(SpotifySessionStore.class);

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final DatabaseClient databaseClient;
    private final SecureRandom secureRandom = new SecureRandom();

    public SpotifySessionStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public static String lookupId(String token) {
        return HexFormat.of().formatHex(sha256("lookup:" + token));
    }

    public Mono<Void> save(SpotifySession session) {
        return databaseClient.sql("""
                        INSERT INTO spotify_sessions (id, spotify_user_id, refresh_token_enc, last_used_at)
                        VALUES (:id, :spotifyUserId, :refreshTokenEnc, :lastUsedAt)
                        """)
                .bind("id", lookupId(session.token()))
                .bind("spotifyUserId", session.spotifyUserId())
                .bind("refreshTokenEnc", encrypt(session.token(), session.refreshToken()))
                .bind("lastUsedAt", System.currentTimeMillis())
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Mono<Void> recordRefresh(SpotifySession session) {
        return databaseClient.sql("""
                        UPDATE spotify_sessions
                        SET refresh_token_enc = :refreshTokenEnc, last_used_at = :lastUsedAt
                        WHERE id = :id
                        """)
                .bind("refreshTokenEnc", encrypt(session.token(), session.refreshToken()))
                .bind("lastUsedAt", System.currentTimeMillis())
                .bind("id", lookupId(session.token()))
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Mono<SpotifySession> load(String token) {
        return databaseClient.sql("""
                        SELECT spotify_user_id AS "spotifyUserId", refresh_token_enc AS "refreshTokenEnc"
                        FROM spotify_sessions
                        WHERE id = :id
                        """)
                .bind("id", lookupId(token))
                .map((row, metadata) -> new StoredSession(
                        row.get("spotifyUserId", String.class),
                        row.get("refreshTokenEnc", String.class)
                ))
                .one()
                .flatMap(stored -> {
                    try {
                        return Mono.just(new SpotifySession(
                                token,
                                stored.spotifyUserId(),
                                decrypt(token, stored.refreshTokenEnc())
                        ));
                    } catch (IllegalStateException error) {
                        log.warn("Could not decrypt a stored Spotify session; treating it as signed out.");
                        return Mono.empty();
                    }
                });
    }

    public Mono<Void> delete(String token) {
        return databaseClient.sql("DELETE FROM spotify_sessions WHERE id = :id")
                .bind("id", lookupId(token))
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Mono<Void> deleteAllForUser(String spotifyUserId) {
        return databaseClient.sql("DELETE FROM spotify_sessions WHERE spotify_user_id = :spotifyUserId")
                .bind("spotifyUserId", spotifyUserId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Mono<Void> moveToAccount(String legacyUserId, String accountId) {
        return databaseClient.sql("UPDATE spotify_sessions SET spotify_user_id = :accountId WHERE spotify_user_id = :legacyUserId")
                .bind("accountId", accountId)
                .bind("legacyUserId", legacyUserId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Mono<Void> deleteIdleSince(long cutoffMs) {
        return databaseClient.sql("DELETE FROM spotify_sessions WHERE last_used_at < :cutoff")
                .bind("cutoff", cutoffMs)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private String encrypt(String token, String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(token), new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array()
            );
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Could not encrypt the Spotify refresh token.", error);
        }
    }

    private String decrypt(String token, String encoded) {
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey(token),
                    new GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES)
            );

            byte[] plaintext = cipher.doFinal(payload, GCM_IV_BYTES, payload.length - GCM_IV_BYTES);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IllegalStateException("Could not decrypt the Spotify refresh token.", error);
        }
    }

    private SecretKeySpec encryptionKey(String token) {
        return new SecretKeySpec(sha256("key:" + token), "AES");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("SHA-256 is not available.", error);
        }
    }

    private record StoredSession(String spotifyUserId, String refreshTokenEnc) {
    }
}
