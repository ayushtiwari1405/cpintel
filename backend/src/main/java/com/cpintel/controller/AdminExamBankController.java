package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.events.EventsDto;
import com.cpintel.questions.ExamBankDto;
import com.cpintel.questions.ExamPublishService;
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

import java.util.List;

/**
 * An examination's questions from the question bank, and publishing them to the judge.
 *
 * <p>Admins only, and only for examinations in classrooms they run: the {@code {eventId}} in
 * every path is checked by {@code ClassroomAccessInterceptor}. TAs write questions in the bank;
 * putting them on the judge is an admin's decision.
 */
@RestController
@RequestMapping("/api/v1/admin/events/{eventId}")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_CONSOLE)
@Tag(name = "Admin", description = "Contests, examinations, monitoring and session logs")
@SecurityRequirement(name = "bearerAuth")
public class AdminExamBankController {

    private final ExamPublishService publishing;

    @GetMapping("/bank")
    @Operation(summary = "The examination's bank questions, what is on the judge, and what can be chosen")
    public ResponseEntity<ApiResponse<ExamBankDto.State>> state(@PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(publishing.state(eventId)));
    }

    @PutMapping("/bank/questions")
    @Operation(summary = "Choose the examination's questions, in order (before the start)")
    public ResponseEntity<ApiResponse<ExamBankDto.State>> choose(
        @AuthenticationPrincipal Long adminId, @PathVariable Long eventId,
        @Valid @RequestBody ExamBankDto.SelectionRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(publishing.choose(adminId, eventId, req.questionIds(), httpReq)));
    }

    @PostMapping("/bank/publish")
    @Operation(summary = "Publish the chosen questions to the judge (before the start)",
        description = "Creates the examination's contest on the classroom's judge once, uploads each "
            + "question, labels them A, B, C… and sets the examination's problem list. Safe to repeat: "
            + "a publish that stopped part way is finished by publishing again.")
    public ResponseEntity<ApiResponse<ExamBankDto.State>> publish(
        @AuthenticationPrincipal Long adminId, @PathVariable Long eventId, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(publishing.publish(adminId, eventId, httpReq)));
    }

    @PostMapping("/bank/questions/{questionId}/republish")
    @Operation(summary = "Replace one published question on the judge, e.g. to fix a test during the paper",
        description = "Same problem and label; needs confirm=true, and the question must have passed its "
            + "check at its new version. An optional notice goes to every candidate. Submissions are "
            + "not rejudged here: use the rejudge for that problem.")
    public ResponseEntity<ApiResponse<ExamBankDto.State>> republish(
        @AuthenticationPrincipal Long adminId, @PathVariable Long eventId, @PathVariable Long questionId,
        @Valid @RequestBody ExamBankDto.RepublishRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(publishing.republish(adminId, eventId, questionId, req, httpReq)));
    }

    @PostMapping("/announcements")
    @Operation(summary = "Tell everyone sitting the examination something",
        description = "Shown in the arena from the start of the paper, within about a minute.")
    public ResponseEntity<ApiResponse<List<EventsDto.Announcement>>> announce(
        @AuthenticationPrincipal Long adminId, @PathVariable Long eventId,
        @Valid @RequestBody EventsDto.AnnouncementRequest req, HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(publishing.announce(adminId, eventId, req.message(), httpReq)));
    }
}
