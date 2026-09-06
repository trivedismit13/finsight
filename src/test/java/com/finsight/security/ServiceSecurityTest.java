package com.finsight.security;

import com.finsight.dto.request.CreateExpenseRequest;
import com.finsight.model.User;
import com.finsight.model.Role;
import com.finsight.repository.UserRepository;
import com.finsight.service.ExpenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ServiceSecurityTest {

    @Autowired
    private ExpenseService expenseService;

    @Autowired
    private UserRepository userRepository;

    private User employeeUser;
    private User financeAdminUser;

    @BeforeEach
    void setup() {
        userRepository.deleteAll();

        employeeUser = new User();
        employeeUser.setName("Employee");
        employeeUser.setEmail("employee@example.com");
        employeeUser.setPassword("hashed");
        employeeUser.setRole(Role.EMPLOYEE);
        userRepository.save(employeeUser);

        financeAdminUser = new User();
        financeAdminUser.setName("Finance Admin");
        financeAdminUser.setEmail("finance_admin@example.com");
        financeAdminUser.setPassword("hashed");
        financeAdminUser.setRole(Role.FINANCE_ADMIN);
        userRepository.save(financeAdminUser);
    }

    

    

    @Test
    @WithMockCustomUser(roles = "FINANCE_ADMIN")
    void testFinanceAdminCanDeleteExpense_AccessDeniedNotThrown() {
        // We might get ResourceNotFoundException because expense 1L doesn't exist, but we should NOT get AccessDeniedException
        assertThrows(com.finsight.exception.ResourceNotFoundException.class, () -> {
            expenseService.deleteExpense(1L, financeAdminUser.getUserId());
        });
    }

    @Test
    void testNoUserThrowsResourceNotFoundException() {
        CreateExpenseRequest req = new CreateExpenseRequest();
        req.setAmount(new BigDecimal("100"));
        req.setCategory(com.finsight.model.ExpenseCategory.MEALS);
        req.setExpenseDate(LocalDate.now());

        assertThrows(com.finsight.exception.ResourceNotFoundException.class, () -> {
            expenseService.createExpense(req, "TEST_KEY", 9999L);
        });
    }
}
