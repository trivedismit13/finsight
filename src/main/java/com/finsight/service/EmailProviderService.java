package com.finsight.service;

import com.finsight.model.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailProviderService {

    public boolean sendEmail(Notification notification) {
        log.info("[EMAIL] Sending {} to userId={}: {}", notification.getType(), notification.getUserId().getUserId(), notification.getPayload());
        // 80% success rate mock — replace with real email provider call
        return Math.random() > 0.2;
    }
}
