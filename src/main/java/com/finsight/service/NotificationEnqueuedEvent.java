package com.finsight.service;

public class NotificationEnqueuedEvent {
    private final Long notificationId;

    public NotificationEnqueuedEvent(Long notificationId) {
        this.notificationId = notificationId;
    }

    public Long getNotificationId() {
        return notificationId;
    }
}
