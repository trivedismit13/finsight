package com.finsight.service;

import lombok.Getter;

@Getter
public class ReportJobCreatedEvent {
    private final Long jobId;

    public ReportJobCreatedEvent(Long jobId) {
        this.jobId = jobId;
    }
}
