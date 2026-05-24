package com.checkSheet.service;

import com.checkSheet.config.JwtService;
import com.checkSheet.entity.RefreshToken;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.RefreshTokenRepository;
import com.checkSheet.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UtilityService utilityService;

    @Value("${app.jwtRefreshTokenExpiration:3600000}")
    private Long jwtRefreshTokenExpirationInMs = 3600000L;

    public RefreshTokenServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService, AuthenticationManager authenticationManager,
         RefreshTokenRepository refreshTokenRepository, UtilityService utilityService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.refreshTokenRepository = refreshTokenRepository;
        this.utilityService = utilityService;
    }

    @Override
    public String generateRefreshToken(String username, Boolean isNewLogin, String deviceType) throws CustomException {
        Optional<User> user = userRepository.findByUsernameIgnoreCase(username);
        if (!user.isPresent()) {
            throw new CustomException("User not found", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        // Atomic upsert via Postgres ON CONFLICT — the prior
        // read-then-save pattern raced on V1.29's UNIQUE(user_id,
        // device_type) constraint when two concurrent logins for the
        // same (user, deviceType) both saw "no row" and both INSERTed.
        // The losing INSERT bubbled up through login()'s catch-all as
        // a misleading "Invalid username or password" 400.
        //
        // isNewLogin === true expires the row at NOW + ttl (rotates
        // the token on real logins). isNewLogin === false (the
        // refresh-token rotation path) keeps the existing expiry; we
        // pass the existing row's expiry through so the upsert leaves
        // it intact even if the row already existed.
        Instant expiry;
        if (isNewLogin) {
            expiry = Instant.now().plusMillis(jwtRefreshTokenExpirationInMs);
        } else {
            // Carry over the existing row's expiry so a refresh
            // doesn't accidentally extend the session past the original
            // login's window.
            expiry = refreshTokenRepository.findByUserAndDeviceType(user.get(), deviceType)
                .map(RefreshToken::getExpiryDate)
                .orElseGet(() -> Instant.now().plusMillis(jwtRefreshTokenExpirationInMs));
        }
        String token = UUID.randomUUID().toString();
        refreshTokenRepository.upsertRefreshToken(user.get().getId(), deviceType, token, expiry);
        return token;
    }


}
