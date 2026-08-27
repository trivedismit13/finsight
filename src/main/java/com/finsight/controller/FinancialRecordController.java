package com.finsight.controller;

import com.finsight.dto.request.CreateRecordRequest;
import com.finsight.dto.request.UpdateRecordRequest;
import com.finsight.dto.response.ApiResponse;
import com.finsight.dto.response.RecordResponse;
import com.finsight.service.FinancialRecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class FinancialRecordController {
    private final FinancialRecordService service;
    private Long resolveUserId(UserDetails principal) {
        if (principal instanceof com.finsight.security.CustomUserDetails) {
            return ((com.finsight.security.CustomUserDetails) principal).getUserId();
        }
        throw new RuntimeException("Authenticated user not found or invalid type");
    }

    @GetMapping
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<RecordResponse>>> getAllRecords(
            @RequestParam(value = "page", required = false) Integer pageNumber,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate,
            org.springframework.data.domain.Pageable pageable,
            @AuthenticationPrincipal UserDetails principal) {

        if (pageNumber != null && pageNumber < 0) {
            throw new com.finsight.exception.InvalidRequestException("Page number cannot be less than zero.");
        }

        if (type != null && !type.matches("^(INCOME|EXPENSE)$")) {
            throw new com.finsight.exception.InvalidRequestException("Type must be INCOME or EXPENSE");
        }

        Long actorId = resolveUserId(principal);
        boolean isAdmin = principal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        return ResponseEntity.ok(new ApiResponse<>("Records fetched", service.getAllRecords(type, category, startDate, endDate, pageable, actorId, isAdmin)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RecordResponse>> create(
            @Valid @RequestBody CreateRecordRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        Long actorId = resolveUserId(principal);
        RecordResponse rec = service.createRecord(req, actorId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("Record created", rec));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RecordResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRecordRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        Long actorId = resolveUserId(principal);
        RecordResponse rec = service.updateRecord(id, req, actorId);
        return ResponseEntity.ok(new ApiResponse<>("Record updated", rec));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails principal) {
        Long actorId = resolveUserId(principal);
        service.deleteRecord(id, actorId);
        return ResponseEntity.noContent().build();
    }
}
