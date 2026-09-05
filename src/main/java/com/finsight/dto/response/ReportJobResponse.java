package com.finsight.dto.response;

import com.finsight.model.ReportJob;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class ReportJobResponse {
    private Long jobId;
    private String period;
    private String status;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public static ReportJobResponse fromEntity(ReportJob job) {
        ReportJobResponse response = new ReportJobResponse();
        response.setJobId(job.getJobId());
        response.setPeriod(job.getPeriod());
        response.setStatus(job.getStatus());
        response.setFailureReason(job.getFailureReason());
        response.setCreatedAt(job.getCreatedAt());
        response.setCompletedAt(job.getCompletedAt());
        return response;
    }
}
