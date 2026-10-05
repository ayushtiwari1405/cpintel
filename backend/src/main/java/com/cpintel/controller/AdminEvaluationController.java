package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.evaluation.EvaluationDto;
import com.cpintel.evaluation.EvaluationService;
import com.cpintel.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Evaluating an examination, as its admins run it: who marks what, and every mark.
 *
 * <p>Under {@code /admin/events/{eventId}}, so {@code ClassroomAccessInterceptor} keeps an admin
 * to the examinations of the classrooms they run.
 */
@RestController
@RequestMapping("/api/v1/admin/events/{eventId}/evaluation")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_CONSOLE)
@Tag(name = "Admin", description = "Marking examinations by hand")
@SecurityRequirement(name = "bearerAuth")
public class AdminEvaluationController {

    private final EvaluationService evaluation;

    @GetMapping
    @Operation(summary = "Who marks what on this examination, the classroom's TAs and the roster")
    public ResponseEntity<ApiResponse<EvaluationDto.AssignmentBoard>> board(
        @PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(evaluation.board(eventId)));
    }

    @PostMapping("/assignments")
    @Operation(summary = "Give a TA a question, a range of usernames, or that question for "
        + "that range")
    public ResponseEntity<ApiResponse<EvaluationDto.AssignmentBoard>> assign(
        @AuthenticationPrincipal Long adminId, @PathVariable Long eventId,
        @Valid @RequestBody EvaluationDto.AssignmentRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(evaluation.assign(adminId, eventId, req, httpReq)));
    }

    @DeleteMapping("/assignments/{assignmentId}")
    @Operation(summary = "Take a piece of marking back from a TA; marks already given stay")
    public ResponseEntity<ApiResponse<EvaluationDto.AssignmentBoard>> unassign(
        @AuthenticationPrincipal Long adminId, @PathVariable Long eventId,
        @PathVariable Long assignmentId, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            evaluation.unassign(adminId, eventId, assignmentId, httpReq)));
    }

    @GetMapping("/sheet")
    @Operation(summary = "Every student and question, with the submission to read and the mark")
    public ResponseEntity<ApiResponse<EvaluationDto.Sheet>> sheet(
        @AuthenticationPrincipal Long adminId, @PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(evaluation.adminSheet(adminId, eventId)));
    }

    @GetMapping("/submissions/{userId}/{label}")
    @Operation(summary = "The submission a marker reads, with its source")
    public ResponseEntity<ApiResponse<EvaluationDto.Submission>> submission(
        @AuthenticationPrincipal Long adminId, @PathVariable Long eventId,
        @PathVariable Long userId, @PathVariable String label) {
        return ResponseEntity.ok(ApiResponse.ok(
            evaluation.adminSubmission(adminId, eventId, userId, label)));
    }

    @PutMapping("/marks")
    @Operation(summary = "Set or clear one mark",
        description = "Only between the end of the examination and its being marked done.")
    public ResponseEntity<ApiResponse<EvaluationDto.Cell>> mark(
        @AuthenticationPrincipal Long adminId, @PathVariable Long eventId,
        @Valid @RequestBody EvaluationDto.MarkRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(evaluation.adminMark(adminId, eventId, req, httpReq)));
    }
}
