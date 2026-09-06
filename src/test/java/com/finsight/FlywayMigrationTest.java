package com.finsight;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles("test")
public class FlywayMigrationTest {

    @Autowired
    private Flyway flyway;

    @Test
    void testMigration() {
        flyway.clean();
        flyway.migrate();
        assertTrue(true);
    }
}
