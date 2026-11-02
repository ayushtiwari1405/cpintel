package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.questions.QuestionBankDto;
import com.cpintel.questions.QuestionBankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A classroom's question bank, for its admins and its TAs.
 *
 * <p>Not under {@code /admin}: TAs are ordinary accounts, so {@link QuestionBankService}
 * decides per classroom who may read and edit, and refuses everyone else. An examination
 * session never reaches any of it, since {@code ExamModeFilter} allows such a session only its
 * own paper.
 */
@RestController
@RequestMapping("/api/v1/question-bank")
@RequiredArgsConstructor
@Tag(name = "Question bank", description = "Exam questions written in CPIntel")
@SecurityRequirement(name = "bearerAuth")
public class QuestionBankController {

    private final QuestionBankService bank;

    @GetMapping("/classrooms")
    @Operation(summary = "The classrooms whose question bank you can open")
    public ResponseEntity<ApiResponse<List<QuestionBankDto.BankClassroom>>> classrooms(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(bank.myClassrooms(userId)));
    }

    @GetMapping("/classrooms/{classroomId}/questions")
    @Operation(summary = "A classroom's questions, most recently changed first")
    public ResponseEntity<ApiResponse<List<QuestionBankDto.QuestionSummary>>> list(
        @AuthenticationPrincipal Long userId, @PathVariable Long classroomId,
        @RequestParam(defaultValue = "false") boolean archived) {
        return ResponseEntity.ok(ApiResponse.ok(bank.list(userId, classroomId, archived)));
    }

    @PostMapping("/classrooms/{classroomId}/questions")
    @Operation(summary = "Write a new question")
    public ResponseEntity<ApiResponse<QuestionBankDto.QuestionDetail>> create(
        @AuthenticationPrincipal Long userId, @PathVariable Long classroomId,
        @Valid @RequestBody QuestionBankDto.QuestionRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(bank.create(userId, classroomId, req, httpReq)));
    }

    @GetMapping("/questions/{questionId}")
    @Operation(summary = "One question, with its list of tests")
    public ResponseEntity<ApiResponse<QuestionBankDto.QuestionDetail>> get(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId) {
        return ResponseEntity.ok(ApiResponse.ok(bank.get(userId, questionId)));
    }

    @PutMapping("/questions/{questionId}")
    @Operation(summary = "Save a question",
        description = "Refused with 409 if someone saved it after the version you name.")
    public ResponseEntity<ApiResponse<QuestionBankDto.QuestionDetail>> update(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId,
        @Valid @RequestBody QuestionBankDto.QuestionRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(bank.update(userId, questionId, req, httpReq)));
    }

    @PostMapping("/questions/{questionId}/archive")
    @Operation(summary = "Archive a question: hidden from the bank, nothing deleted")
    public ResponseEntity<ApiResponse<QuestionBankDto.QuestionDetail>> archive(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(bank.setArchived(userId, questionId, true, httpReq)));
    }

    @PostMapping("/questions/{questionId}/restore")
    @Operation(summary = "Bring an archived question back")
    public ResponseEntity<ApiResponse<QuestionBankDto.QuestionDetail>> restore(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(bank.setArchived(userId, questionId, false, httpReq)));
    }

    @PostMapping(value = "/questions/{questionId}/tests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Add tests",
        description = "name.in files, each with an optional name.ans or name.out, added after the "
            + "existing ones in the natural order of their names.")
    public ResponseEntity<ApiResponse<QuestionBankDto.QuestionDetail>> addTests(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId,
        @RequestParam("files") List<MultipartFile> files,
        @RequestParam(defaultValue = "false") boolean sample,
        @RequestParam Integer version, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            bank.addTests(userId, questionId, sample, version, files, httpReq)));
    }

    @DeleteMapping("/questions/{questionId}/tests/{testId}")
    @Operation(summary = "Remove one test")
    public ResponseEntity<ApiResponse<QuestionBankDto.QuestionDetail>> deleteTest(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId,
        @PathVariable Long testId, @RequestParam Integer version, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            bank.deleteTest(userId, questionId, testId, version, httpReq)));
    }

    @PutMapping("/questions/{questionId}/tests/order")
    @Operation(summary = "Reorder the samples, or the secret tests")
    public ResponseEntity<ApiResponse<QuestionBankDto.QuestionDetail>> reorder(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId,
        @Valid @RequestBody QuestionBankDto.TestOrderRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(bank.reorder(userId, questionId, req, httpReq)));
    }

    @GetMapping("/questions/{questionId}/tests/{testId}/{part:input|answer}")
    @Operation(summary = "Download a test's input or answer")
    public ResponseEntity<byte[]> download(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId,
        @PathVariable Long testId, @PathVariable String part) {
        QuestionBankDto.Download file = bank.download(userId, questionId, testId, "answer".equals(part));
        HttpHeaders headers = new HttpHeaders();
        // Always a download, never rendered: test data is whatever the setter uploaded.
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(file.name(), StandardCharsets.UTF_8).build());
        headers.setContentLength(file.bytes().length);
        return new ResponseEntity<>(file.bytes(), headers, HttpStatus.OK);
    }
}
