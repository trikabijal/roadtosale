package com.checkSheet.repository;

import com.checkSheet.entity.RefreshToken;
import com.checkSheet.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findById(Long id);

    @Transactional
    int deleteByToken(String token);

    Optional<RefreshToken> findByUserAndDeviceType(User user, String deviceType);

    Optional<RefreshToken> findByTokenAndDeviceType(String token, String deviceType);

    /**
     * Atomic upsert of the per-user/per-device refresh-token row.
     *
     * <p>V1.29 added {@code UNIQUE(user_id, device_type)}. Under
     * concurrent logins for the same (user, deviceType) the prior
     * read-then-save pattern raced: two requests both saw "no row",
     * both INSERTed, one hit the unique violation, and the login's
     * generic catch-all surfaced it as
     * {@code 400 "Invalid username or password"}. Postgres
     * {@code ON CONFLICT DO UPDATE} is a single atomic statement —
     * no race window, no JPA session-poisoning to recover from.
     */
    @Modifying
    @Transactional
    @Query(value =
        "INSERT INTO refresh_token (user_id, device_type, token, expiry_date, created_at) " +
        "VALUES (:userId, :deviceType, :token, :expiryDate, NOW()) " +
        "ON CONFLICT (user_id, device_type) DO UPDATE " +
        "   SET token = EXCLUDED.token, expiry_date = EXCLUDED.expiry_date",
        nativeQuery = true)
    void upsertRefreshToken(
        @Param("userId") Long userId,
        @Param("deviceType") String deviceType,
        @Param("token") String token,
        @Param("expiryDate") Instant expiryDate);
}
