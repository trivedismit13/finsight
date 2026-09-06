package com.finsight.controller;

import com.finsight.model.Expense;
import com.finsight.model.User;
import com.finsight.repository.ExpenseRepository;
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
@com.finsight.security.WithMockCustomUser(roles = "FINANCE_ADMIN")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class PaginationFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        expenseRepository.deleteAll();
        
        testUser = userRepository.findByEmail("test@example.com").orElseGet(() -> {
            User u = new User();
            u.setEmail("test@example.com");
            u.setPassword("password");
            u.setName("Test User");
            u.setRole(com.finsight.model.Role.MANAGER);
            return userRepository.save(u);
        });

        Expense r1 = new Expense();
        r1.setAmount(new BigDecimal("100.00"));
        r1.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        r1.setExpenseDate(LocalDate.of(2026, 8, 1));
        r1.setCreatedBy(testUser);
        expenseRepository.save(r1);

        Expense r2 = new Expense();
        r2.setAmount(new BigDecimal("200.00"));
        r2.setCategory(com.finsight.model.ExpenseCategory.OTHER);
        r2.setExpenseDate(LocalDate.of(2026, 8, 15));
        r2.setCreatedBy(testUser);
        r2.setStatus(com.finsight.model.ExpenseStatus.APPROVED);
        expenseRepository.save(r2);

        Expense deleted = new Expense();
        deleted.setAmount(new BigDecimal("50.00"));
        deleted.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        deleted.setExpenseDate(LocalDate.of(2026, 8, 20));
        deleted.setCreatedBy(testUser);
        deleted.setDeleted(true);
        expenseRepository.save(deleted);

        com.finsight.security.CustomUserDetails principal = new com.finsight.security.CustomUserDetails(
                "test@example.com", "password", true, true, true, true,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLOYEE")),
                testUser.getUserId()
        );
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, "password", principal.getAuthorities())
        );
    }

    @Test
    public void testPaginationAndSizeLimit() throws Exception {
        // Fetch with excessive size
        mockMvc.perform(get("/api/expenses?page=0&size=10000")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageable.pageSize", is(100))) // clamped to max-page-size
                .andExpect(jsonPath("$.data.content", hasSize(2))); // deleted is hidden
    }

    @Test
    public void testFiltering() throws Exception {
        // Filter by type
        mockMvc.perform(get("/api/expenses?status=DRAFT")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1))) // only r1, deleted is hidden
                .andExpect(jsonPath("$.data.content[0].category", is("MEALS")));

        // Filter by date range
        mockMvc.perform(get("/api/expenses?startDate=2026-08-01&endDate=2026-08-10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)));

        // Invalid date range
        mockMvc.perform(get("/api/expenses?startDate=2026-08-10&endDate=2026-08-01")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testSortValidation() throws Exception {
        mockMvc.perform(get("/api/expenses?sort=password")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testInvalidPage() throws Exception {
        mockMvc.perform(get("/api/expenses?page=-1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
