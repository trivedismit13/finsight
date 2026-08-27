package com.finsight.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.dto.request.CreateRecordRequest;
import com.finsight.dto.request.LoginRequest;
import com.finsight.dto.request.RegisterRequest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ValidationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @com.finsight.security.WithMockCustomUser(username = "admin@example.com", roles = "ADMIN")
    public void testCreateRecordValidation() throws Exception {
        CreateRecordRequest req = new CreateRecordRequest();
        req.setAmount(new BigDecimal("-500.00")); // negative amount
        req.setType("INVALID"); // invalid enum
        req.setCategory(""); // blank category
        req.setRecordDate(null); // missing date

        mockMvc.perform(post("/api/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
                
        // Too many decimals
        req.setAmount(new BigDecimal("10.123"));
        req.setType("EXPENSE");
        req.setCategory("Food");
        req.setRecordDate(LocalDate.now());
        
        mockMvc.perform(post("/api/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
                
        // Too large amount
        req.setAmount(new BigDecimal("999999999999999999.99"));
        
        mockMvc.perform(post("/api/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testAuthValidation() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setName("Test");
        req.setEmail("not-an-email"); // invalid email
        req.setPassword("short"); // too short
        
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
                
        LoginRequest login = new LoginRequest();
        login.setEmail("not-an-email");
        login.setPassword("");
        
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isBadRequest());
    }
}
