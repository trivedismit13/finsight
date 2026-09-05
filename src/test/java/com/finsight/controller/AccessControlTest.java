package com.finsight.controller;

import com.finsight.repository.AuditLogRepository;
import com.finsight.security.CustomUserDetailsService;
import com.finsight.security.JwtAuthFilter;
import com.finsight.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.SignatureAlgorithm;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ExpenseController.class, AuditController.class, ReportController.class})
@Import({com.finsight.security.SecurityConfig.class, com.finsight.security.JwtAuthFilter.class, com.finsight.security.JwtUtil.class, org.springframework.data.web.config.SpringDataJacksonConfiguration.class})
@TestPropertySource(properties = {
    "jwt.secret=mySuperSecretKeyForTestingWhichNeedsToBeAtLeast32BytesLong!",
    "jwt.access-token-expiration-ms=3600000"
})
class AccessControlTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private com.finsight.service.ExpenseService expenseService;
    @MockBean private com.finsight.repository.UserRepository userRepository;
    @MockBean private com.finsight.service.AuditLogService auditLogService;
    @Autowired private JwtUtil jwtUtil;
    @MockBean private CustomUserDetailsService customUserDetailsService;
    @MockBean private com.finsight.service.ReportExportService reportExportService;
    @MockBean private com.finsight.repository.ReportJobRepository reportJobRepository;

    @Test
    void testMissingJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/expenses"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Authentication failed"));
    }

    @Test
    void testInvalidJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/expenses")
                .header("Authorization", "Bearer invalid.jwt.token.random.string"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Invalid or expired token"));
    }

    @Test
    void testExpiredJwt_returns401() throws Exception {
        String secretKey = "mySuperSecretKeyForTestingWhichNeedsToBeAtLeast32BytesLong!";
        String expiredToken = Jwts.builder()
                .setSubject("test@test.com")
                .claim("role", "EMPLOYEE")
                .claim("userId", 1L)
                .setIssuedAt(new Date(System.currentTimeMillis() - 100000))
                .setExpiration(new Date(System.currentTimeMillis() - 1000)) // Expired
                .signWith(Keys.hmacShaKeyFor(secretKey.getBytes()), SignatureAlgorithm.HS256)
                .compact();

        mockMvc.perform(get("/api/expenses")
                .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Invalid or expired token"));
    }

    @Test
    void testTamperedJwt_returns401() throws Exception {
        String validToken = jwtUtil.generateAccessToken("test@test.com", "EMPLOYEE", 1L);
        String tamperedToken = validToken.substring(0, validToken.length() - 5) + "abcde";

        mockMvc.perform(get("/api/expenses")
                .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Invalid or expired token"));
    }
}
