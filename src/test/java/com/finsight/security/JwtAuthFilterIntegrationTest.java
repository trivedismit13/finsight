package com.finsight.security;

import com.finsight.model.Role;
import com.finsight.model.User;
import com.finsight.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class JwtAuthFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User lockedUser;
    private User disabledUser;
    private User activeUser;

    @BeforeEach
    void setup() {
        lockedUser = new User();
        lockedUser.setName("Locked User");
        lockedUser.setEmail("locked@example.com");
        lockedUser.setPassword("pass");
        lockedUser.setRole(Role.EMPLOYEE);
        lockedUser.setActive(true);
        lockedUser.setLockedUntil(LocalDateTime.now().plusDays(1));
        userRepository.save(lockedUser);

        disabledUser = new User();
        disabledUser.setName("Disabled User");
        disabledUser.setEmail("disabled@example.com");
        disabledUser.setPassword("pass");
        disabledUser.setRole(Role.EMPLOYEE);
        disabledUser.setActive(false);
        userRepository.save(disabledUser);

        activeUser = new User();
        activeUser.setName("Active User");
        activeUser.setEmail("active@example.com");
        activeUser.setPassword("pass");
        activeUser.setRole(Role.EMPLOYEE);
        activeUser.setActive(true);
        userRepository.save(activeUser);
    }

    @Test
    void testLockedUserIsRejectedWithValidJwt() throws Exception {
        String token = jwtUtil.generateAccessToken(lockedUser.getEmail(), lockedUser.getRole().name(), lockedUser.getUserId());

        mockMvc.perform(get("/api/expenses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testDisabledUserIsRejectedWithValidJwt() throws Exception {
        String token = jwtUtil.generateAccessToken(disabledUser.getEmail(), disabledUser.getRole().name(), disabledUser.getUserId());

        mockMvc.perform(get("/api/expenses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testActiveUserIsAllowedWithValidJwt() throws Exception {
        String token = jwtUtil.generateAccessToken(activeUser.getEmail(), activeUser.getRole().name(), activeUser.getUserId());

        mockMvc.perform(get("/api/expenses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
