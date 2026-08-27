package com.finsight.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@com.finsight.security.WithMockCustomUser(roles = "ADMIN")
@org.springframework.test.context.ActiveProfiles("test")
public class ConnectionPoolSaturationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void determineHikariSaturationPoint() throws InterruptedException {
        int maxConnections = 10; // default Hikari pool size
        System.out.println("--- Starting Connection Pool Saturation Test ---");
        System.out.println("Default Hikari Pool Size is " + maxConnections);
        
        int concurrentThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch latch = new CountDownLatch(concurrentThreads);
        
        AtomicInteger successful = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        
        List<Future<?>> futures = new ArrayList<>();
        
        for (int i = 0; i < concurrentThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    // Simulate holding a connection for a transaction
                    try (Connection conn = dataSource.getConnection()) {
                        successful.incrementAndGet();
                        Thread.sleep(200); // Hold connection for 200ms
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }));
        }
        
        latch.await(10, TimeUnit.SECONDS);
        
        System.out.println("Threads Attempted: " + concurrentThreads);
        System.out.println("Successful Connections Acquired: " + successful.get());
        System.out.println("Failed Connections (Timeout): " + failed.get());
        
        // Let's test actual throughput
        System.out.println("--- Throughput Test ---");
        long start = System.currentTimeMillis();
        int totalRequests = 100;
        CountDownLatch tpLatch = new CountDownLatch(totalRequests);
        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try (Connection conn = dataSource.getConnection()) {
                    Thread.sleep(20); // 20ms DB operation
                } catch (Exception ignored) {}
                tpLatch.countDown();
            });
        }
        tpLatch.await(10, TimeUnit.SECONDS);
        long end = System.currentTimeMillis();
        System.out.println("Time to process 100 requests (20ms each) with pool size 10: " + (end - start) + "ms");
        
        executor.shutdownNow();
    }
}
