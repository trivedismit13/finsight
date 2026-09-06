package com.finsight.service;

import com.finsight.dto.request.*;
import com.finsight.dto.response.*;
import com.finsight.exception.AccountLockedException;
import com.finsight.exception.InvalidCredentialsException;
import com.finsight.exception.InvalidRefreshTokenException;
import com.finsight.model.RefreshToken;
import com.finsight.model.Role;
import com.finsight.model.User;
import com.finsight.repository.RefreshTokenRepository;
import com.finsight.repository.UserRepository;
import com.finsight.security.JwtUtil;
import com.finsight.config.SecurityLockoutConfig;
import com.finsight.config.RefreshTokenConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final SecurityLockoutConfig securityLockoutConfig;
    private final RefreshTokenConfig refreshTokenConfig;
    private final org.springframework.context.ApplicationContext applicationContext;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already in use");
        }

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        // Role is ALWAYS EMPLOYEE on self-registration — cannot be overridden by client input
        user.setRole(Role.EMPLOYEE);

        userRepository.save(user);

        return UserResponse.builder()
                .userId(user.getUserId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }

    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
    public AuthResponse login(LoginRequest request) {
        // Fetch user with pessimistic lock to prevent concurrent increment of failed login attempts
        User user = userRepository.findByEmailForUpdate(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));

        // Check lockout BEFORE checking password — do not allow timing attacks through this path
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new AccountLockedException("Account is locked until " + user.getLockedUntil());
        }

        // Check isActive
        if (!user.isActive()) {
            throw new InvalidCredentialsException("Invalid credentials");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
            if (user.getFailedLoginAttempts() >= securityLockoutConfig.getMaxAttempts()) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(securityLockoutConfig.getDurationMinutes()));
            }
            userRepository.save(user);
            throw new InvalidCredentialsException("Invalid credentials");
        }

        // Correct password — reset lockout state
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        String accessToken = jwtUtil.generateAccessToken(user.getEmail(), user.getRole().name(), user.getUserId());
        String rawRefreshToken = issueRefreshToken(user, null);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .expiresIn(jwtUtil.getExpirationMs() / 1000)
                .tokenType("Bearer")
                .build();
    }

    @Transactional
    public String issueRefreshToken(User user) {
        return issueRefreshToken(user, null);
    }

    @Transactional
    String issueRefreshToken(User user, Long replacedByTokenId) {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(hashToken(rawToken));
        refreshToken.setExpiresAt(LocalDateTime.now().plusDays(refreshTokenConfig.getExpirationDays()));
        refreshToken.setReplacedByTokenId(replacedByTokenId);

        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    @Transactional
    public AuthResponse refreshAccessToken(RefreshTokenRequest request) {
        String tokenHash = hashToken(request.getRefreshToken());
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));

        int updatedRows = refreshTokenRepository.consumeTokenAtomically(token.getTokenId(), LocalDateTime.now());
        if (updatedRows == 0) {
            // Check if it failed because it was expired
            if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new InvalidRefreshTokenException("Refresh token expired");
            }
            // If not expired, it means it was already consumed (revoked = true).
            // THEFT SIGNAL: this token was already rotated out; someone replayed it
            applicationContext.getBean(AuthService.class).revokeAllTokensForUser(token.getUser());
            throw new InvalidRefreshTokenException("Session invalidated, please log in again");
        }

        User user = token.getUser();
        String newRawRefreshToken = issueRefreshToken(user, token.getTokenId());
        String newAccessToken = jwtUtil.generateAccessToken(user.getEmail(), user.getRole().name(), user.getUserId());

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRawRefreshToken)
                .expiresIn(jwtUtil.getExpirationMs() / 1000)
                .tokenType("Bearer")
                .build();
    }

    @Transactional
    public void logout(LogoutRequest request) {
        String tokenHash = hashToken(request.getRefreshToken());
        // Idempotent: if not found, silently ignore
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            token.setRevoked(true);
            token.setRevokedAt(LocalDateTime.now());
            refreshTokenRepository.save(token);
        });
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void revokeAllTokensForUser(User user) {
        List<RefreshToken> activeTokens = refreshTokenRepository.findAllByUser_UserIdAndRevokedFalse(user.getUserId());
        for (RefreshToken rt : activeTokens) {
            rt.setRevoked(true);
            rt.setRevokedAt(LocalDateTime.now());
        }
        refreshTokenRepository.saveAll(activeTokens);
    }

    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(refreshTokenConfig.getHashAlgorithm());
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(refreshTokenConfig.getHashAlgorithm() + " not available", e);
        }
    }
}
