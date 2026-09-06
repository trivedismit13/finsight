package com.finsight.controller;

import com.finsight.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AuditController {
    private final AuditLogService auditLogService;

    @GetMapping
    public org.springframework.http.ResponseEntity<com.finsight.dto.response.ApiResponse<org.springframework.data.domain.Page<com.finsight.dto.response.AuditLogResponse>>> getAllAuditLogs(
            org.springframework.data.domain.Pageable pageable) {
        return org.springframework.http.ResponseEntity.ok(new com.finsight.dto.response.ApiResponse<>("Audit logs fetched", auditLogService.getAllAuditLogs(pageable)));
    }
}
