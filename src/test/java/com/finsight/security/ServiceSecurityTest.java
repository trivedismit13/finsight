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
    private ExpenseService recordService;

    @Autowired
    private UserRepository userRepository;

    private User viewerUser;
    private User adminUser;

    @BeforeEach
    void setup() {
        userRepository.deleteAll();

        viewerUser = new User();
        viewerUser.setName("Viewer");
        viewerUser.setEmail("employee@example.com");
        viewerUser.setPassword("hashed");
        viewerUser.setRole(Role.EMPLOYEE);
        userRepository.save(viewerUser);

        adminUser = new User();
        adminUser.setName("Admin");
        adminUser.setEmail("finance_admin@example.com");
        adminUser.setPassword("hashed");
        adminUser.setRole(Role.FINANCE_ADMIN);
        userRepository.save(adminUser);
    }

    

    

    @Test
    @WithMockCustomUser(roles = "FINANCE_ADMIN")
    void testAdminCanDeleteRecord_AccessDeniedNotThrown() {
        // We might get ResourceNotFoundException because record 1L doesn't exist, but we should NOT get AccessDeniedException
        assertThrows(com.finsight.exception.ResourceNotFoundException.class, () -> {
            recordService.deleteExpense(1L, adminUser.getUserId());
        });
    }

    @Test
    void testNoUserThrowsResourceNotFoundException() {
        CreateExpenseRequest req = new CreateExpenseRequest();
        req.setAmount(new BigDecimal("100"));
        req.setCategory(com.finsight.model.ExpenseCategory.MEALS.name());
        req.setExpenseDate(LocalDate.now());

        assertThrows(com.finsight.exception.ResourceNotFoundException.class, () -> {
            recordService.createExpense(req, 1L);
        });
    }
}
