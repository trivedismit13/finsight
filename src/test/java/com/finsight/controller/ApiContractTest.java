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
    private FinancialRecordRepository recordRepository;

    @Autowired
    private UserRepository userRepository;

    private Long recordId;

    @BeforeEach
    void setUp() {
        recordRepository.deleteAll();

        User testUser = userRepository.findByEmail("admin@example.com").orElseGet(() -> {
            User u = new User();
            u.setEmail("admin@example.com");
            u.setPassword("password");
            u.setName("Admin User");
            u.setRole(com.finsight.model.Role.ADMIN);
            return userRepository.save(u);
        });

        FinancialRecord r = new FinancialRecord();
        r.setAmount(new BigDecimal("100.00"));
        r.setType("EXPENSE");
        r.setCategory("Food");
        r.setRecordDate(LocalDate.now());
        r.setCreatedBy(testUser);
        r = recordRepository.save(r);
        recordId = r.getRecordId();
    }

    @Test
    @com.finsight.security.WithMockCustomUser(username = "admin@example.com", roles = "ADMIN")
    public void testDeleteRecordReturns204NoContent() throws Exception {
        mockMvc.perform(delete("/api/records/" + recordId))
                .andExpect(status().isNoContent()); // HTTP 204
    }
}
