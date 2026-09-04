import os

with open('src/test/java/com/finsight/service/FailureInjectionDbTest.java', 'r') as f:
    content = f.read()

replacement = """
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new com.finsight.security.CustomUserDetails(1L, "user@test.com", "pass", java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"))), null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"))
            )
        );
"""

content = content.replace('budgetService.createOrUpdateBudget(1L, "Food", "2023-10", new BigDecimal("5000.00"));', replacement + 'budgetService.createOrUpdateBudget(1L, "Food", "2023-10", new BigDecimal("5000.00"));')

with open('src/test/java/com/finsight/service/FailureInjectionDbTest.java', 'w') as f:
    f.write(content)
