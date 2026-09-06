package com.finsight.service;

import com.finsight.dto.request.LoginRequest;
import com.finsight.dto.request.RefreshTokenRequest;
import com.finsight.exception.AccountLockedException;
import com.finsight.exception.InvalidCredentialsException;
import com.finsight.exception.InvalidRefreshTokenException;
import com.finsight.model.RefreshToken;
import com.finsight.model.Role;
import com.finsight.model.User;
import com.finsight.repository.RefreshTokenRepository;
import com.finsight.repository.UserRepository;
import com.finsight.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @Mock private com.finsight.config.SecurityLockoutConfig securityLockoutConfig;
    @Mock private com.finsight.config.RefreshTokenConfig refreshTokenConfig;
    @Mock private org.springframework.context.ApplicationContext applicationContext;

    @InjectMocks
    private AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setUserId(1L);
        testUser.setEmail("test@test.com");
        testUser.setPassword("encodedPassword");
        testUser.setRole(Role.EMPLOYEE);
        testUser.setActive(true);
        
        lenient().when(securityLockoutConfig.getMaxAttempts()).thenReturn(5);
        lenient().when(securityLockoutConfig.getDurationMinutes()).thenReturn(15);
        lenient().when(refreshTokenConfig.getExpirationDays()).thenReturn(7);
        lenient().when(refreshTokenConfig.getHashAlgorithm()).thenReturn("SHA-256");
        lenient().when(jwtUtil.getExpirationMs()).thenReturn(900000L);
    }

    /**
     * Given: user with failedLoginAttempts = 4.
     * When: login is attempted with the wrong password.
     * Then: failedLoginAttempts == 5 and lockedUntil is set to ~now() + 15 minutes.
     */
    @Test
    void testLoginLockout_locksAfterFiveFailedAttempts() {
        testUser.setFailedLoginAttempts(4);
        when(userRepository.findByEmailForUpdate("test@test.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrong", testUser.getPassword())).thenReturn(false);

        LoginRequest req = new LoginRequest();
        req.setEmail("test@test.com");
        req.setPassword("wrong");

        assertThrows(InvalidCredentialsException.class, () -> authService.login(req));

        assertEquals(5, testUser.getFailedLoginAttempts());
        assertNotNull(testUser.getLockedUntil());
        // Should be approximately now + 15 minutes
        assertTrue(testUser.getLockedUntil().isAfter(LocalDateTime.now().plusMinutes(14)));
        assertTrue(testUser.getLockedUntil().isBefore(LocalDateTime.now().plusMinutes(16)));
        verify(userRepository).save(testUser);
    }

    /**
     * Given: user with lockedUntil in the future.
     * When: login is attempted even with the correct password.
     * Then: AccountLockedException is thrown and passwordEncoder.matches is never called.
     */
    @Test
    void testLoginLockout_rejectsLoginWhileLocked() {
        testUser.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userRepository.findByEmailForUpdate("test@test.com")).thenReturn(Optional.of(testUser));

        LoginRequest req = new LoginRequest();
        req.setEmail("test@test.com");
        req.setPassword("correct");

        assertThrows(AccountLockedException.class, () -> authService.login(req));
        // Password must never be checked while account is locked
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    /**
     * Given: user with failedLoginAttempts = 3.
     * When: login succeeds with the correct password.
     * Then: failedLoginAttempts == 0, lockedUntil == null, lastLogin updated.
     */
    @Test
    void testLoginSuccess_resetsLockoutFields() {
        testUser.setFailedLoginAttempts(3);
        when(userRepository.findByEmailForUpdate("test@test.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("correct", testUser.getPassword())).thenReturn(true);
        when(jwtUtil.generateAccessToken(anyString(), anyString(), anyLong())).thenReturn("access_token");

        LoginRequest req = new LoginRequest();
        req.setEmail("test@test.com");
        req.setPassword("correct");

        authService.login(req);

        assertEquals(0, testUser.getFailedLoginAttempts());
        assertNull(testUser.getLockedUntil());
        assertNotNull(testUser.getLastLogin());
        verify(userRepository, atLeastOnce()).save(testUser);
    }

    /**
     * Given: a valid, non-revoked RefreshToken for a user.
     * When: refreshAccessToken is called with the corresponding raw token.
     * Then: old token has revoked=true; a new token is saved; new token hash != old raw token hash.
     */
    @Test
    void testRefreshTokenRotation_oldTokenRevokedNewTokenIssued() {
        String rawToken = "raw_token_value";
        String hash = authService.hashToken(rawToken);

        RefreshToken oldToken = new RefreshToken();
        oldToken.setTokenId(10L);
        oldToken.setUser(testUser);
        oldToken.setTokenHash(hash);
        oldToken.setExpiresAt(LocalDateTime.now().plusDays(1));
        oldToken.setRevoked(false);

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(oldToken));
        when(refreshTokenRepository.consumeTokenAtomically(eq(oldToken.getTokenId()), any(LocalDateTime.class))).thenReturn(1);
        when(jwtUtil.generateAccessToken(anyString(), anyString(), anyLong())).thenReturn("new_access");
        
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> {
            RefreshToken t = i.getArgument(0);
            t.setTokenId(101L);
            return t;
        });

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken(rawToken);

        var resp = authService.refreshAccessToken(req);

        // Only one save call: for the new token (old token was updated atomically)
        verify(refreshTokenRepository, times(1)).save(any(RefreshToken.class));

        // The returned refresh token must not be the same as the original
        assertNotEquals(rawToken, resp.getRefreshToken());
        assertEquals("new_access", resp.getAccessToken());
        assertEquals(900, resp.getExpiresIn());
    }

    /**
     * Given: a RefreshToken that has already been rotated (revoked=true) — theft scenario.
     * When: refreshAccessToken is called again with that stale token.
     * Then: InvalidRefreshTokenException is thrown; ALL active tokens for that user are revoked.
     */
    @Test
    void testRefreshTokenReuseDetection_revokesAllUserTokensOnReplay() {
        String rawToken = "stolen_raw_token";
        String hash = authService.hashToken(rawToken);

        RefreshToken stolenToken = new RefreshToken();
        stolenToken.setUser(testUser);
        stolenToken.setTokenHash(hash);
        stolenToken.setRevoked(true); // Already used!

        RefreshToken otherActiveSession = new RefreshToken();
        otherActiveSession.setRevoked(false);

        stolenToken.setExpiresAt(LocalDateTime.now().plusDays(1)); // Prevents NPE

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(stolenToken));
        when(refreshTokenRepository.consumeTokenAtomically(eq(stolenToken.getTokenId()), any(LocalDateTime.class))).thenReturn(0);
        when(refreshTokenRepository.findAllByUser_UserIdAndRevokedFalse(testUser.getUserId()))
                .thenReturn(List.of(otherActiveSession));

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken(rawToken);
        
        when(applicationContext.getBean(AuthService.class)).thenReturn(authService);

        assertThrows(InvalidRefreshTokenException.class, () -> authService.refreshAccessToken(req));

        // Every active session must be revoked
        assertTrue(otherActiveSession.isRevoked());
        verify(refreshTokenRepository).saveAll(List.of(otherActiveSession));
    }

    /**
     * Given: a RefreshToken with expiresAt in the past.
     * When: refreshAccessToken is called.
     * Then: InvalidRefreshTokenException is thrown.
     */
    @Test
    void testExpiredRefreshToken_rejected() {
        String rawToken = "expired_raw";
        String hash = authService.hashToken(rawToken);

        RefreshToken expiredToken = new RefreshToken();
        expiredToken.setTokenHash(hash);
        expiredToken.setExpiresAt(LocalDateTime.now().minusDays(1));
        expiredToken.setRevoked(false);

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(expiredToken));
        when(refreshTokenRepository.consumeTokenAtomically(eq(expiredToken.getTokenId()), any(LocalDateTime.class))).thenReturn(0);

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken(rawToken);

        assertThrows(InvalidRefreshTokenException.class, () -> authService.refreshAccessToken(req));
    }

    /**
     * Given: disabled user account.
     * When: login is attempted.
     * Then: InvalidCredentialsException is thrown — same generic message as wrong password (no information leak).
     */
    @Test
    void testLogin_disabledUser_returnsGenericError() {
        testUser.setActive(false);
        when(userRepository.findByEmailForUpdate("test@test.com")).thenReturn(Optional.of(testUser));

        LoginRequest req = new LoginRequest();
        req.setEmail("test@test.com");
        req.setPassword("correct");

        assertThrows(InvalidCredentialsException.class, () -> authService.login(req));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }
    @Test
    void testRegister_success() {
        com.finsight.dto.request.RegisterRequest req = new com.finsight.dto.request.RegisterRequest();
        req.setName("Test User");
        req.setEmail("newuser@test.com");
        req.setPassword("plaintext");

        when(userRepository.findByEmail("newuser@test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("plaintext")).thenReturn("hashed_password");

        com.finsight.dto.response.UserResponse response = authService.register(req);

        assertEquals("Test User", response.getName());
        assertEquals("newuser@test.com", response.getEmail());
        assertEquals(Role.EMPLOYEE.name(), response.getRole());

        verify(userRepository).save(any(User.class));
    }

    @Test
    void testRegister_duplicateEmail_throwsIllegalArgumentException() {
        com.finsight.dto.request.RegisterRequest req = new com.finsight.dto.request.RegisterRequest();
        req.setEmail("test@test.com");
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(testUser));
        assertThrows(IllegalArgumentException.class, () -> authService.register(req));
    }

    @Test
    void testLogin_lockExpired_allowsLoginAndResetsLock() {
        testUser.setFailedLoginAttempts(5);
        testUser.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        when(userRepository.findByEmailForUpdate("test@test.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("correct", testUser.getPassword())).thenReturn(true);
        when(jwtUtil.generateAccessToken(anyString(), anyString(), anyLong())).thenReturn("access_token");

        LoginRequest req = new LoginRequest();
        req.setEmail("test@test.com");
        req.setPassword("correct");

        authService.login(req);

        assertEquals(0, testUser.getFailedLoginAttempts());
        assertNull(testUser.getLockedUntil());
        verify(userRepository, atLeastOnce()).save(testUser);
    }

    @Test
    void testLogin_lockExactlyNow_allowsLogin() {
        testUser.setFailedLoginAttempts(5);
        testUser.setLockedUntil(LocalDateTime.now());
        when(userRepository.findByEmailForUpdate("test@test.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("correct", testUser.getPassword())).thenReturn(true);
        when(jwtUtil.generateAccessToken(anyString(), anyString(), anyLong())).thenReturn("access_token");

        LoginRequest req = new LoginRequest();
        req.setEmail("test@test.com");
        req.setPassword("correct");

        authService.login(req);

        assertEquals(0, testUser.getFailedLoginAttempts());
        assertNull(testUser.getLockedUntil());
    }

    @Test
    void testLogin_stateMachine_normalToLockedToNormal() {
        when(userRepository.findByEmailForUpdate("test@test.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrong", testUser.getPassword())).thenReturn(false);

        LoginRequest req = new LoginRequest();
        req.setEmail("test@test.com");
        req.setPassword("wrong");

        for (int i = 0; i < 4; i++) {
            testUser.setFailedLoginAttempts(i);
            assertThrows(InvalidCredentialsException.class, () -> authService.login(req));
        }

        testUser.setFailedLoginAttempts(4);
        assertThrows(InvalidCredentialsException.class, () -> authService.login(req));

        assertEquals(5, testUser.getFailedLoginAttempts());
        assertNotNull(testUser.getLockedUntil());
        assertTrue(testUser.getLockedUntil().isAfter(LocalDateTime.now()));

        assertThrows(AccountLockedException.class, () -> authService.login(req));

        testUser.setLockedUntil(LocalDateTime.now().minusSeconds(1));
        when(passwordEncoder.matches("correct", testUser.getPassword())).thenReturn(true);
        when(jwtUtil.generateAccessToken(anyString(), anyString(), anyLong())).thenReturn("access_token");
        req.setPassword("correct");

        authService.login(req);

        assertEquals(0, testUser.getFailedLoginAttempts());
        assertNull(testUser.getLockedUntil());
    }
}
