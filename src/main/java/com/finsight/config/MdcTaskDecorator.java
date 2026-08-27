package com.finsight.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;
import java.util.UUID;

public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capture context from parent thread
        Map<String, String> parentContextMap = MDC.getCopyOfContextMap();

        return () -> {
            try {
                // Restore parent context (if any)
                if (parentContextMap != null) {
                    MDC.setContextMap(parentContextMap);
                }
                
                // Explicitly generate a NEW traceId for this async execution
                MDC.put(MdcLoggingFilter.TRACE_ID_KEY, UUID.randomUUID().toString());
                
                runnable.run();
            } finally {
                // Always clear MDC to prevent leakage on thread pool reuse
                MDC.clear();
            }
        };
    }
}
