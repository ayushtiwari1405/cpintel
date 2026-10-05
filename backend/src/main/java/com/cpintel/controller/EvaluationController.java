package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.evaluation.EvaluationDto;
import com.cpintel.evaluation.EvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Marking, as a teaching assistant does it.
 *
 * <p>Open to any signed-in account; {@link EvaluationService} answers each one with only the
 * examinations and the answers that account was given to mark, and "no such examination" for
 * the rest. An examination session cannot reach any of it: {@code ExamModeFilter} allows a
 * session signed in for a paper nothing but that paper.
 */
@RestController
@RequestMapping("/api/v1/evaluation")
@RequiredArgsConstructor
@Tag(name = "Evaluation", description = "Marking examinations as a teaching assistant")
@SecurityRequirement(name = "bearerAuth")
public class EvaluationController {

    private final EvaluationService evaluation;

    @GetMapping("/exams")
    @Operation(summary = "The examinations you have marking on")
    public ResponseEntity<ApiResponse<List<EvaluationDto.TaExam>>> exams(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(evaluation.myExams(userId)));
    }

    @GetMapping("/exams/{eventId}")
    @Operation(summary = "The students and questions you mark on one examination")
    public ResponseEntity<ApiResponse<EvaluationDto.Sheet>> sheet(
        @AuthenticationPrincipal Long userId, @PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(evaluation.taSheet(userId, eventId)));
    }

    @GetMapping("/exams/{eventId}/submissions/{studentId}/{label}")
    @Operation(summary = "The submission to read for one student and question, with its source")
    public ResponseEntity<ApiResponse<EvaluationDto.Submission>> submission(
        @AuthenticationPrincipal Long userId, @PathVariable Long eventId,
        @PathVariable Long studentId, @PathVariable String label) {
        return ResponseEntity.ok(ApiResponse.ok(
            evaluation.taSubmission(userId, eventId, studentId, label)));
    }

    @PutMapping("/exams/{eventId}/marks")
    @Operation(summary = "Set or clear one mark",
        description = "Only between the end of the examination and its being marked done.")
    public ResponseEntity<ApiResponse<EvaluationDto.Cell>> mark(
        @AuthenticationPrincipal Long userId, @PathVariable Long eventId,
        @Valid @RequestBody EvaluationDto.MarkRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(evaluation.taMark(userId, eventId, req, httpReq)));
    }
}
