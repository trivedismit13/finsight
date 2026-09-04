package com.finsight.controller;

import com.finsight.dto.response.ApiResponse;
import com.finsight.dto.response.ReportJobResponse;
import com.finsight.model.ReportJob;
import com.finsight.repository.ReportJobRepository;
import com.finsight.service.ReportExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {
    private final ReportExportService reportService;
    private final ReportJobRepository reportJobRepository;
    private Long resolveUserId(UserDetails principal) {
        if (principal instanceof com.finsight.security.CustomUserDetails) {
            return ((com.finsight.security.CustomUserDetails) principal).getUserId();
        }
        throw new RuntimeException("Authenticated user not found or invalid type");
    }

    @PostMapping("/expense-summary")
    public ResponseEntity<ApiResponse<Long>> requestExport(
            @RequestParam String period,
            @AuthenticationPrincipal UserDetails principal) {
        if (!period.matches("^\\d{4}-\\d{2}$")) {
            throw new com.finsight.exception.InvalidRequestException("Period must be in YYYY-MM format");
        }
        Long userId = resolveUserId(principal);
        Long jobId = reportService.requestReport(userId, period);
        return ResponseEntity.accepted().body(new ApiResponse<>("Report job queued", jobId));
    }

    @GetMapping("/expense-summary/{jobId}")
    public ResponseEntity<ApiResponse<ReportJob>> getStatus(@PathVariable Long jobId,
            @AuthenticationPrincipal UserDetails principal) {
        Long userId = resolveUserId(principal);
        boolean isAdmin = principal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        
        ReportJob job = reportService.getReportStatus(jobId, userId, isAdmin);
        return ResponseEntity.ok(new ApiResponse<>("Job status", job));
    }

    @GetMapping("/expense-summary/{jobId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadReport(@PathVariable Long jobId,
            @AuthenticationPrincipal UserDetails principal) {
        Long userId = resolveUserId(principal);
        boolean isAdmin = principal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        
        org.springframework.core.io.Resource resource = reportService.getReportDownloadResource(jobId, userId, isAdmin);

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report-" + jobId + ".csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
                .body(resource);
    }
}
