package com.finsight.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

public class MdcLoggingFilter extends OncePerRequestFilter {


    public static final String TRACE_ID_KEY = "traceId";
    public static final String CORRELATION_ID_KEY = "correlationId";
    public static final String USER_ID_KEY = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        try {
            // 1. Generate unique trace ID for this request execution
            String traceId = UUID.randomUUID().toString();
            MDC.put(TRACE_ID_KEY, traceId);

            // 2. Extract or generate durable correlation ID
            String correlationId = request.getHeader("X-Correlation-Id");
            if (correlationId == null || !correlationId.matches("^[a-zA-Z0-9-]{1,36}$")) {
                correlationId = UUID.randomUUID().toString();
            }
            MDC.put(CORRELATION_ID_KEY, correlationId);

            // 3. Extract userId from authenticated principal if available
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof org.springframework.security.core.userdetails.UserDetails) {
                Object principal = authentication.getPrincipal();
                if (principal instanceof com.finsight.security.CustomUserDetails) {
                    MDC.put(USER_ID_KEY, String.valueOf(((com.finsight.security.CustomUserDetails) principal).getUserId()));
                } else {
                    MDC.put(USER_ID_KEY, authentication.getName());
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            // ALWAYS clear MDC
            MDC.clear();
        }
    }
}
