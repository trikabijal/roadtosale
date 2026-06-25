package com.auditpro.roadtosale.security;

import com.auditpro.roadtosale.config.RoadToSaleProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates JWT access/refresh tokens. Access tokens carry
 * {@code userId} and {@code dealershipId} claims (PRD §7 #5); refresh tokens
 * carry the same plus {@code type=refresh} so they can't be used as access tokens.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_DEALERSHIP_ID = "dealershipId";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    /** Minimum HMAC-SHA256 key length: 256 bits / 8 = 32 bytes. */
    private static final int MIN_SECRET_BYTES = 32;

    /** The well-known dev default from application.yml — never acceptable in prod. */
    static final String DEV_DEFAULT_SECRET = "change-me-in-env-dev-only-secret-please";

    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtService(RoadToSaleProperties props, Environment environment) {
        String secret = props.getAuth().getJwtSecret();
        boolean devOrTest = isDevOrTest(environment);

        // Fail-fast in prod (or any non dev/test profile): a null/blank/short secret,
        // or the known dev default, would silently accept forged tokens. Refuse to start.
        if (!devOrTest) {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "JWT secret (roadtosale.auth.jwt-secret / JWT_SECRET) is required in production");
            }
            if (DEV_DEFAULT_SECRET.equals(secret)) {
                throw new IllegalStateException(
                        "JWT secret is the dev default; set a real JWT_SECRET in production");
            }
            if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "JWT secret must be at least " + MIN_SECRET_BYTES + " bytes (256-bit) in production");
            }
        } else if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES
                || DEV_DEFAULT_SECRET.equals(secret)) {
            // Dev/test may run on the default; warn so it is never mistaken for prod-safe.
            // No padding: a real >=32-byte secret is still required, but the dev default
            // already exceeds 32 bytes so HMAC key construction succeeds.
            log.warn("Using a development JWT secret. Set a strong JWT_SECRET (>= {} bytes) for production.",
                    MIN_SECRET_BYTES);
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "JWT secret must be at least " + MIN_SECRET_BYTES + " bytes; configure roadtosale.auth.jwt-secret");
            }
        }

        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = props.getAuth().getAccessTokenTtlSeconds();
        this.refreshTtlSeconds = props.getAuth().getRefreshTokenTtlSeconds();
    }

    /** True when running under the {@code dev} or {@code test} profile (lenient secret). */
    private static boolean isDevOrTest(Environment environment) {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            // No explicit profile -> treat as non-dev (production-leaning) and fail-fast.
            return false;
        }
        return Arrays.stream(active).anyMatch(p -> p.equals("dev") || p.equals("test"));
    }

    public String issueAccessToken(UUID userId, UUID dealershipId) {
        return issue(userId, dealershipId, TYPE_ACCESS, accessTtlSeconds);
    }

    public String issueRefreshToken(UUID userId, UUID dealershipId) {
        return issue(userId, dealershipId, TYPE_REFRESH, refreshTtlSeconds);
    }

    private String issue(UUID userId, UUID dealershipId, String type, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_USER_ID, userId.toString())
                .claim(CLAIM_DEALERSHIP_ID, dealershipId.toString())
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /** Parse + verify an access token. Throws on invalid/expired/wrong-type. */
    public AuthenticatedUser parseAccessToken(String token) {
        Claims claims = parse(token);
        requireType(claims, TYPE_ACCESS);
        return toUser(claims);
    }

    /** Parse + verify a refresh token. Throws on invalid/expired/wrong-type. */
    public AuthenticatedUser parseRefreshToken(String token) {
        Claims claims = parse(token);
        requireType(claims, TYPE_REFRESH);
        return toUser(claims);
    }

    private Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Invalid or expired token");
        }
    }

    private void requireType(Claims claims, String expected) {
        if (!expected.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new InvalidTokenException("Wrong token type");
        }
    }

    private AuthenticatedUser toUser(Claims claims) {
        try {
            return new AuthenticatedUser(
                    UUID.fromString(claims.get(CLAIM_USER_ID, String.class)),
                    UUID.fromString(claims.get(CLAIM_DEALERSHIP_ID, String.class)));
        } catch (RuntimeException e) {
            throw new InvalidTokenException("Malformed token claims");
        }
    }

    /** Thrown when a token fails verification; mapped to 401 by callers/filter. */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
