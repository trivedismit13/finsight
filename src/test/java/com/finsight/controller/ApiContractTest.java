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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private UserRepository userRepository;

    private Long expenseId;

    @BeforeEach
    void setUp() {
        expenseRepository.deleteAll();

        User testUser = userRepository.findByEmail("finance_admin@example.com").orElseGet(() -> {
            User u = new User();
            u.setEmail("finance_admin@example.com");
            u.setPassword("password");
            u.setName("Admin User");
            u.setRole(com.finsight.model.Role.FINANCE_ADMIN);
            return userRepository.save(u);
        });

        Expense r = new Expense();
        r.setAmount(new BigDecimal("100.00"));
        r.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        r.setExpenseDate(LocalDate.now());
        r.setCreatedBy(testUser);
        r = expenseRepository.save(r);
        expenseId = r.getExpenseId();
    }

    @Test
    @com.finsight.security.WithMockCustomUser(username = "finance_admin@example.com", roles = "FINANCE_ADMIN")
    public void testDeleteRecordReturns204NoContent() throws Exception {
        mockMvc.perform(delete("/api/expenses/" + expenseId))
                .andExpect(status().isNoContent()); // HTTP 204
    }
}
