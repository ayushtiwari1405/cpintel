package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cpintel.questions.QuestionBankDto;
import com.cpintel.questions.QuestionBankService;
import com.cpintel.questions.QuestionCheckService;
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

import com.cpintel.exception.ApiException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
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

    /** The compressed package; nginx allows this route the same (see cpintel.conf.template). */
    static final long MAX_PACKAGE_BYTES = 72L * 1024 * 1024;

    private final QuestionBankService bank;
    private final QuestionCheckService checks;
    private final ObjectMapper objectMapper;

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

    @PostMapping(value = "/classrooms/{classroomId}/questions/import",
        consumes = {"application/zip", "application/x-zip-compressed", MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @Operation(summary = "Make a question from a problem package",
        description = "The zip as the request body, as DOMjudge imports it: problem.yaml, "
            + "domjudge-problem.ini, problem_statement/, data/sample, data/secret, submissions/accepted. "
            + "At most 72 MB. Answers with the new question and anything the import changed or left out.")
    public ResponseEntity<ApiResponse<QuestionBankDto.ImportResult>> importPackage(
        @AuthenticationPrincipal Long userId, @PathVariable Long classroomId,
        @RequestParam(required = false) String fileName, HttpServletRequest httpReq) throws IOException {
        long declared = httpReq.getContentLengthLong();
        if (declared > MAX_PACKAGE_BYTES) {
            throw ApiException.badRequest("A package can be at most " + (MAX_PACKAGE_BYTES >> 20) + " MB.");
        }
        InputStream body = new CappedInputStream(httpReq.getInputStream(), MAX_PACKAGE_BYTES);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
            bank.importPackage(userId, classroomId, body, fileName, httpReq)));
    }

    @GetMapping("/package-template.zip")
    @Operation(summary = "An example package to start a question from",
        description = "Every file the import reads, filled in for a small problem, with a README.")
    public ResponseEntity<byte[]> packageTemplate() {
        byte[] zip = bank.packageTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(QuestionBankService.PACKAGE_TEMPLATE_NAME, StandardCharsets.UTF_8).build());
        headers.setContentLength(zip.length);
        return new ResponseEntity<>(zip, headers, HttpStatus.OK);
    }

        @GetMapping("/questions/{questionId}/package.zip")
    @Operation(summary = "Download the question as a problem package",
        description = "The same zip that publishing sends to the judge.")
    public ResponseEntity<byte[]> exportPackage(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId) {
        QuestionBankDto.Download file = bank.exportPackage(userId, questionId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(file.name(), StandardCharsets.UTF_8).build());
        headers.setContentLength(file.bytes().length);
        return new ResponseEntity<>(file.bytes(), headers, HttpStatus.OK);
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

    @PostMapping("/questions/{questionId}/check")
    @Operation(summary = "Check the model solution against every test",
        description = "Runs in the background; poll GET .../check. With generateMissing, tests without "
            + "an answer first get the model solution's output as theirs. A pass makes the question ready.")
    public ResponseEntity<ApiResponse<QuestionBankDto.Check>> startCheck(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId,
        @RequestBody(required = false) QuestionBankDto.CheckRequest req, HttpServletRequest httpReq) {
        boolean generate = req != null && req.generateMissing();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.ok(checks.start(userId, questionId, generate, httpReq)));
    }

    @GetMapping("/questions/{questionId}/check")
    @Operation(summary = "The latest check of the model solution, or null if there has been none")
    public ResponseEntity<ApiResponse<QuestionBankDto.Check>> latestCheck(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId) {
        return ResponseEntity.ok(ApiResponse.ok(checks.latest(userId, questionId)));
    }

        @PostMapping("/questions/{questionId}/statement/preview")
    @Operation(summary = "Render the statement as it is in the editor, without saving",
        description = "A PDF. Formulas that could not be drawn are listed, URL-encoded JSON, in "
            + "the X-Statement-Problems header.")
    public ResponseEntity<byte[]> preview(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId,
        @Valid @RequestBody QuestionBankDto.PreviewRequest req) {
        return pdf(bank.preview(userId, questionId, req), "preview.pdf");
    }

    @GetMapping("/questions/{questionId}/statement.pdf")
    @Operation(summary = "The statement candidates would read, as saved")
    public ResponseEntity<byte[]> statement(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId) {
        return pdf(bank.statement(userId, questionId), "statement-" + questionId + ".pdf");
    }

    @PutMapping(value = "/questions/{questionId}/statement-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Use your own PDF as the statement")
    public ResponseEntity<ApiResponse<QuestionBankDto.QuestionDetail>> uploadStatementPdf(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId,
        @RequestParam("file") MultipartFile file, @RequestParam Integer version,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            bank.uploadStatementPdf(userId, questionId, version, file, httpReq)));
    }

    @DeleteMapping("/questions/{questionId}/statement-pdf")
    @Operation(summary = "Remove the uploaded PDF and use the written statement again")
    public ResponseEntity<ApiResponse<QuestionBankDto.QuestionDetail>> removeStatementPdf(
        @AuthenticationPrincipal Long userId, @PathVariable Long questionId,
        @RequestParam Integer version, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            bank.removeStatementPdf(userId, questionId, version, httpReq)));
    }

    private ResponseEntity<byte[]> pdf(QuestionBankDto.Rendered rendered, String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.inline()
            .filename(name, StandardCharsets.UTF_8).build());
        headers.setContentLength(rendered.pdf().length);
        headers.setCacheControl("no-store");
        String problems;
        try {
            problems = objectMapper.writeValueAsString(rendered.problems());
        } catch (JsonProcessingException e) {
            problems = "[]";
        }
        headers.set("X-Statement-Problems", URLEncoder.encode(problems, StandardCharsets.UTF_8));
        return new ResponseEntity<>(rendered.pdf(), headers, HttpStatus.OK);
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

    /** Stops reading a request body past a limit, whatever its Content-Length said. */
    static final class CappedInputStream extends FilterInputStream {
        private final long limit;
        private long read;

        CappedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = super.read(buffer, off, len);
            if (n > 0) count(n);
            return n;
        }

        private void count(long n) {
            read += n;
            if (read > limit) {
                throw ApiException.badRequest("A package can be at most " + (limit >> 20) + " MB.");
            }
        }
    }
}
