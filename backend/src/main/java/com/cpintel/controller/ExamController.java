package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.events.EventService;
import com.cpintel.events.EventsDto;
import com.cpintel.events.ExamEventService;
import com.cpintel.groups.ContestMonitorRegistry;
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
import java.util.Map;

/**
 * An examination, as the person sitting it sees it.
 *
 * <p>Deliberately narrow. Somebody can list the examinations they were assigned, open one, read
 * its rules and problems, report what their own client observed, and say that their monitoring
 * is still running. Nothing here can reach the roster, another candidate's session, or any
 * summary of conduct — those live on {@link AdminEventController}, a separate controller rather
 * than the same one with a flag.
 *
 * <p>Every route resolves the examination through the assignment check rather than by id alone,
 * and answers 404 for one that exists but was not assigned to the caller. Saying "forbidden"
 * would confirm that an examination exists and they were left off it, which is a conversation
 * for their invigilator rather than for a probe of the API.
 */
@RestController
@RequestMapping("/api/v1/exams")
@RequiredArgsConstructor
@Tag(name = "Examinations", description = "The examinations assigned to you")
@SecurityRequirement(name = "bearerAuth")
public class ExamController {

    private final EventService events;
    private final ExamEventService examEvents;
    private final ContestMonitorRegistry monitors;
    /** Entering, unlocking and reporting belong to an examination session only. */
    private final com.cpintel.events.ExamSessionGuard examGuard;

    @GetMapping
    @Operation(summary = "The examinations assigned to you")
    public ResponseEntity<ApiResponse<List<EventsDto.EventSummary>>> mine(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(events.mine(userId, "EXAM")));
    }

    @GetMapping("/{examId}")
    @Operation(summary = "One examination: its clock, its problems and how it is monitored")
    public ResponseEntity<ApiResponse<EventsDto.MyExam>> detail(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long examId) {
        return ResponseEntity.ok(ApiResponse.ok(events.myExam(userId, examId)));
    }

    @PostMapping("/{examId}/enter")
    @Operation(summary = "Enter the examination, recording that you did")
    public ResponseEntity<ApiResponse<EventsDto.MyExam>> enter(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long examId) {
        examGuard.requireExamSession(examId);
        return ResponseEntity.ok(ApiResponse.ok(events.enter(userId, examId)));
    }

    /**
     * Opens a live examination with the passwords handed out in the room.
     *
     * <p>This is the third of the three things that have to be true before somebody is inside a
     * paper — they were assigned it, its window is open, and they were given the passwords at
     * the desk. The first two were decided days and minutes ago respectively; only this one is
     * decided by the invigilator standing in the room, which is why it cannot be derived from
     * either of the others. See {@link com.cpintel.events.ExamAccessService}.
     *
     * <p>Rate-limited per candidate rather than per address, because a room full of people
     * sitting the same paper shares one address, and one of them mistyping their code must not
     * lock out the other two hundred.
     */
    @PostMapping("/{examId}/unlock")
    @Operation(summary = "Unlock a live examination with the password you were given")
    public ResponseEntity<ApiResponse<EventsDto.MyExam>> unlock(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long examId,
        @Valid @RequestBody EventsDto.UnlockRequest req,
        HttpServletRequest httpReq) {
        examGuard.requireExamSession(examId);
        return ResponseEntity.ok(ApiResponse.ok(events.unlock(userId, examId, req, httpReq)));
    }

    /**
     * The code this candidate submitted into a paper that is over.
     *
     * <p>Their own work, and nothing else. Not the marks, not the verdicts of anybody else, not
     * the test data — all of that belongs to the judge and to whoever publishes results, and
     * putting any of it here would make a review screen into a results screen that nobody
     * decided to publish.
     *
     * <p><b>Only after the paper has ended.</b> During one there is nothing to recover — the
     * editor still has the code — and a second window onto the same submissions inside a
     * monitored sitting is a surface with no purpose and an obvious misuse.
     */
    @GetMapping("/{examId}/submissions")
    @Operation(summary = "What you submitted into a past examination")
    public ResponseEntity<ApiResponse<List<EventsDto.MySubmission>>> mySubmissions(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long examId) {
        return ResponseEntity.ok(ApiResponse.ok(events.mySubmissions(userId, examId)));
    }

    /** One of those submissions, with the source, to read back into an editor. */
    @GetMapping("/{examId}/submissions/{submissionId}")
    @Operation(summary = "Read back one of your own submissions from a past examination")
    public ResponseEntity<ApiResponse<EventsDto.MySubmission>> mySubmission(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long examId,
        @PathVariable String submissionId) {
        return ResponseEntity.ok(ApiResponse.ok(
            events.mySubmission(userId, examId, submissionId)));
    }

    /**
     * What this candidate's own client observed.
     *
     * Batched and retried by the client, which is why every event carries an id the server
     * treats as idempotent: a dropped connection must be able to resend its queue without
     * turning one absence into five.
     */
    @PostMapping("/{examId}/events")
    @Operation(summary = "Report what your examination client observed")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> report(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long examId,
        @Valid @RequestBody EventsDto.EventReport report) {
        examGuard.requireExamSession(examId);
        int stored = examEvents.report(userId, examId, report);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("stored", stored)));
    }

    /**
     * Says that this candidate's monitoring is still running.
     *
     * Separate from the event report, which only speaks when it has something to say — and the
     * silence of somebody behaving perfectly is indistinguishable from the silence of somebody
     * who closed the window. This is what the submission gate reads.
     */
    @PostMapping("/{examId}/monitor/heartbeat")
    @Operation(summary = "Report that your examination monitoring is still running")
    public ResponseEntity<ApiResponse<Map<String, Long>>> heartbeat(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long examId) {
        examGuard.requireExamSession(examId);
        events.requireAssigned(userId, examId);
        monitors.beat(userId, examId);
        return ResponseEntity.ok(ApiResponse.ok(
            Map.of("intervalSeconds", monitors.intervalSeconds())));
    }
}
