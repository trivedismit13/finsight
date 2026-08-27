package com.finsight.queue;

import com.finsight.service.NotificationEnqueuedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationQueueManager queueManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationEnqueued(NotificationEnqueuedEvent event) {
        queueManager.enqueue(event.getNotificationId());
    }
}
