package com.auditpro.roadtosale.service;

import com.auditpro.roadtosale.domain.User;
import com.auditpro.roadtosale.dto.LoginResponse;
import com.auditpro.roadtosale.dto.RefreshResponse;
import com.auditpro.roadtosale.dto.UserDTO;
import com.auditpro.roadtosale.repo.UserRepository;
import com.auditpro.roadtosale.security.AuthenticatedUser;
import com.auditpro.roadtosale.security.JwtService;
import com.auditpro.roadtosale.web.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Login + refresh. Verifies BCrypt password hashes and issues JWT pairs. */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException.Unauthorized("Invalid username or password"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException.Unauthorized("Invalid username or password");
        }
        String access = jwtService.issueAccessToken(user.getId(), user.getDealershipId());
        String refresh = jwtService.issueRefreshToken(user.getId(), user.getDealershipId());
        return new LoginResponse(access, refresh, UserDTO.from(user));
    }

    @Transactional(readOnly = true)
    public RefreshResponse refresh(String refreshToken) {
        AuthenticatedUser principal;
        try {
            principal = jwtService.parseRefreshToken(refreshToken);
        } catch (JwtService.InvalidTokenException e) {
            throw new ApiException.Unauthorized("Invalid or expired refresh token");
        }
        // Confirm the user still exists (and pick up the authoritative dealership).
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ApiException.Unauthorized("Invalid or expired refresh token"));
        String access = jwtService.issueAccessToken(user.getId(), user.getDealershipId());
        String refresh = jwtService.issueRefreshToken(user.getId(), user.getDealershipId());
        return new RefreshResponse(access, refresh);
    }
}
