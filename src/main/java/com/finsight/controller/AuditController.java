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
    public java.util.List<com.finsight.dto.response.AuditLogResponse> getAllAuditLogs() {
        return auditLogService.getAllAuditLogs();
    }
}
