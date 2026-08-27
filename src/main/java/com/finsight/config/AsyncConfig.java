package com.finsight.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@lombok.RequiredArgsConstructor
public class AsyncConfig {

    private final NotificationConfig notificationConfig;

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(notificationConfig.getWorkerPoolSize());
        executor.setMaxPoolSize(notificationConfig.getWorkerPoolSize());
        // The NotificationQueueManager already handles queueing in-memory via ArrayBlockingQueue
        // We set the executor queue capacity to 0 (SynchronousQueue) so that if the 4 threads are busy,
        // the executor rejects it. BUT wait, NotificationQueueManager currently submits to a thread pool.
        // If we want the executor to just execute immediately, we can give it a small queue or keep the existing bounded queue semantics.
        // Actually, the bounded queue is maintained by `NotificationQueueManager`'s own `ArrayBlockingQueue`.
        // The executor is just pulling from it or running what's submitted.
        // Wait, NotificationQueueManager has `Executors.newFixedThreadPool(workerPoolSize)` and submits tasks from a continuous loop.
        // So the executor's internal queue doesn't need to hold much if the loop blocks on `ArrayBlockingQueue.take()`.
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("notify-worker-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "reportExecutor")
    public Executor reportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // Report generation doesn't have an explicit in-memory queue manager loop like notifications.
        // It's submitted directly from AFTER_COMMIT event listener.
        // We give it a bounded queue.
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("report-worker-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
