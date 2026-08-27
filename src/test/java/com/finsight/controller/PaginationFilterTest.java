package com.finsight.controller;

import com.finsight.model.FinancialRecord;
import com.finsight.model.User;
import com.finsight.repository.FinancialRecordRepository;
import com.finsight.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@com.finsight.security.WithMockCustomUser(roles = "ADMIN")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class PaginationFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FinancialRecordRepository recordRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        recordRepository.deleteAll();
        
        testUser = userRepository.findByEmail("test@example.com").orElseGet(() -> {
            User u = new User();
            u.setEmail("test@example.com");
            u.setPassword("password");
            u.setName("Test User");
            u.setRole(com.finsight.model.Role.ANALYST);
            return userRepository.save(u);
        });

        FinancialRecord r1 = new FinancialRecord();
        r1.setAmount(new BigDecimal("100.00"));
        r1.setType("EXPENSE");
        r1.setCategory("Food");
        r1.setRecordDate(LocalDate.of(2026, 8, 1));
        r1.setCreatedBy(testUser);
        recordRepository.save(r1);

        FinancialRecord r2 = new FinancialRecord();
        r2.setAmount(new BigDecimal("200.00"));
        r2.setType("INCOME");
        r2.setCategory("Salary");
        r2.setRecordDate(LocalDate.of(2026, 8, 15));
        r2.setCreatedBy(testUser);
        recordRepository.save(r2);

        FinancialRecord deleted = new FinancialRecord();
        deleted.setAmount(new BigDecimal("50.00"));
        deleted.setType("EXPENSE");
        deleted.setCategory("Food");
        deleted.setRecordDate(LocalDate.of(2026, 8, 20));
        deleted.setCreatedBy(testUser);
        deleted.setDeleted(true);
        recordRepository.save(deleted);

        com.finsight.security.CustomUserDetails principal = new com.finsight.security.CustomUserDetails(
                "test@example.com", "password", true, true, true, true,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANALYST")),
                testUser.getUserId()
        );
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, "password", principal.getAuthorities())
        );
    }

    @Test
    public void testPaginationAndSizeLimit() throws Exception {
        // Fetch with excessive size
        mockMvc.perform(get("/api/records?page=0&size=10000")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageable.pageSize", is(100))) // clamped to max-page-size
                .andExpect(jsonPath("$.data.content", hasSize(2))); // deleted is hidden
    }

    @Test
    public void testFiltering() throws Exception {
        // Filter by type
        mockMvc.perform(get("/api/records?type=EXPENSE")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1))) // only r1, deleted is hidden
                .andExpect(jsonPath("$.data.content[0].category", is("Food")));

        // Filter by date range
        mockMvc.perform(get("/api/records?startDate=2026-08-01&endDate=2026-08-10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)));

        // Invalid date range
        mockMvc.perform(get("/api/records?startDate=2026-08-10&endDate=2026-08-01")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testSortValidation() throws Exception {
        mockMvc.perform(get("/api/records?sort=password")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testInvalidPage() throws Exception {
        mockMvc.perform(get("/api/records?page=-1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
