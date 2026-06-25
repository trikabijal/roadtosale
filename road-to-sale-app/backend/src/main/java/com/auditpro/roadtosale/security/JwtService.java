package com.auditpro.roadtosale.security;

import com.auditpro.roadtosale.config.RoadToSaleProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates JWT access/refresh tokens. Access tokens carry
 * {@code userId} and {@code dealershipId} claims (PRD §7 #5); refresh tokens
 * carry the same plus {@code type=refresh} so they can't be used as access tokens.
 */
@Service
public class JwtService {

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_DEALERSHIP_ID = "dealershipId";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtService(RoadToSaleProperties props) {
        String secret = props.getAuth().getJwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            // HMAC-SHA256 needs >= 256-bit key; pad short dev secrets deterministically.
            secret = (secret == null ? "" : secret) + "0123456789012345678901234567890123456789";
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = props.getAuth().getAccessTokenTtlSeconds();
        this.refreshTtlSeconds = props.getAuth().getRefreshTokenTtlSeconds();
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
