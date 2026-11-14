package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.labs.LabService;
import com.cpintel.labs.LabsDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A classroom's lab assignments. The classroom in the path is checked by
 * {@code ClassroomAccessInterceptor}; a lab is then only found inside that classroom.
 */
@RestController
@RequestMapping("/api/v1/admin/classrooms/{classroomId}/labs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Lab assignments")
@SecurityRequirement(name = "bearerAuth")
public class AdminLabController {

    private final LabService labs;

    @GetMapping
    @Operation(summary = "The classroom's labs, latest deadline first")
    public ResponseEntity<ApiResponse<List<LabsDto.LabSummary>>> list(@PathVariable Long classroomId) {
        return ResponseEntity.ok(ApiResponse.ok(labs.list(classroomId)));
    }

    @PostMapping("/resolve")
    @Operation(summary = "Look a Codeforces or LeetCode problem link up before adding it")
    public ResponseEntity<ApiResponse<LabsDto.ProblemView>> resolve(
        @PathVariable Long classroomId, @Valid @RequestBody LabsDto.ResolveRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(labs.resolve(req.link())));
    }

    @PostMapping
    @Operation(summary = "Set a lab")
    public ResponseEntity<ApiResponse<LabsDto.LabSummary>> create(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId,
        @Valid @RequestBody LabsDto.LabRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(labs.create(adminId, classroomId, req, httpReq)));
    }

    @GetMapping("/{labId}")
    public ResponseEntity<ApiResponse<LabsDto.LabSummary>> detail(
        @PathVariable Long classroomId, @PathVariable Long labId) {
        return ResponseEntity.ok(ApiResponse.ok(labs.detail(classroomId, labId)));
    }

    @PutMapping("/{labId}")
    @Operation(summary = "Change a lab",
        description = "Changing its window, or whether earlier solves count, rechecks every solve.")
    public ResponseEntity<ApiResponse<LabsDto.LabSummary>> update(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId, @PathVariable Long labId,
        @Valid @RequestBody LabsDto.LabRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(labs.update(adminId, classroomId, labId, req, httpReq)));
    }

    @DeleteMapping("/{labId}")
    public ResponseEntity<ApiResponse<Void>> delete(
        @AuthenticationPrincipal Long adminId, @PathVariable Long classroomId, @PathVariable Long labId,
        HttpServletRequest httpReq) {
        labs.delete(adminId, classroomId, labId, httpReq);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/{labId}/progress")
    @Operation(summary = "Every student's solves and marks")
    public ResponseEntity<ApiResponse<LabsDto.Progress>> progress(
        @PathVariable Long classroomId, @PathVariable Long labId) {
        return ResponseEntity.ok(ApiResponse.ok(labs.progress(classroomId, labId)));
    }

    @PostMapping("/{labId}/check")
    @Operation(summary = "Check everyone's submissions now, in the background")
    public ResponseEntity<ApiResponse<LabsDto.CheckStarted>> check(
        @PathVariable Long classroomId, @PathVariable Long labId) {
        return ResponseEntity.ok(ApiResponse.ok(labs.checkNow(classroomId, labId)));
    }

    @GetMapping("/{labId}/marks.xlsx")
    @Operation(summary = "Marks and solves as a spreadsheet")
    public ResponseEntity<byte[]> workbook(@PathVariable Long classroomId, @PathVariable Long labId) {
        LabService.Workbook book = labs.workbook(classroomId, labId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(com.cpintel.common.Xlsx.CONTENT_TYPE))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(book.fileName()).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(book.bytes());
    }
}
