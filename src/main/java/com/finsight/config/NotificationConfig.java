package com.finsight.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "finsight.notification")
public class NotificationConfig {
    private int workerPoolSize = 4;
    private int queueCapacity = 10000;
    private long processingTimeoutMinutes = 5;
    private Retry retry = new Retry();

    public int getWorkerPoolSize() { return workerPoolSize; }
    public void setWorkerPoolSize(int workerPoolSize) { this.workerPoolSize = workerPoolSize; }
    
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    
    public long getProcessingTimeoutMinutes() { return processingTimeoutMinutes; }
    public void setProcessingTimeoutMinutes(long processingTimeoutMinutes) { this.processingTimeoutMinutes = processingTimeoutMinutes; }
    
    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }

    public static class Retry {
        private long baseDelayMs = 1000;
        private long maxDelayMs = 30000;
        private int maxRetries = 3;
        private long jitterMs = 500;

        public long getBaseDelayMs() { return baseDelayMs; }
        public void setBaseDelayMs(long baseDelayMs) { this.baseDelayMs = baseDelayMs; }
        public long getMaxDelayMs() { return maxDelayMs; }
        public void setMaxDelayMs(long maxDelayMs) { this.maxDelayMs = maxDelayMs; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public long getJitterMs() { return jitterMs; }
        public void setJitterMs(long jitterMs) { this.jitterMs = jitterMs; }
    }
}
