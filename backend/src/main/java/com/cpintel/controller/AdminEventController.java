package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.common.Languages;
import com.cpintel.events.EventService;
import com.cpintel.events.EventsDto;
import com.cpintel.events.ExamFlagService;
import com.cpintel.events.ExamLeaderboardService;
import com.cpintel.events.ExamMonitorService;
import com.cpintel.events.ExamPasswordService;
import com.cpintel.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Contests and examinations, as an admin runs them.
 *
 * <p>The whole surface is console-only. A candidate's view lives on {@link ExamController},
 * which is a separate controller rather than this one with a role check inside: there is no
 * shared code path where a filter could be forgotten, and nothing a candidate calls can reach
 * the roster, the monitoring dashboard or anybody else's session log.
 *
 * <p>Both kinds of event are managed here because they are the same object with different rules
 * — see {@link EventService}. What separates them on this surface is only that the monitoring
 * and log endpoints are meaningful for an examination and mostly empty for a contest.
 */
@RestController
@RequestMapping("/api/v1/admin/events")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_CONSOLE)
@Tag(name = "Admin", description = "Contests, examinations, monitoring and session logs")
@SecurityRequirement(name = "bearerAuth")
public class AdminEventController {

    private final EventService events;
    private final ExamMonitorService monitor;
    private final ExamFlagService flags;
    private final ExamLeaderboardService leaderboard;
    private final ExamPasswordService passwords;

    // ------------------------------------------------------------------ reads

    @GetMapping
    @Operation(summary = "Every contest, or every examination")
    public ResponseEntity<ApiResponse<List<EventsDto.EventSummary>>> list(
        @RequestParam(defaultValue = "EXAM") String kind) {
        return ResponseEntity.ok(ApiResponse.ok(events.list(kind)));
    }

    @GetMapping("/{eventId}")
    @Operation(summary = "One event, with its problems and everyone assigned to it")
    public ResponseEntity<ApiResponse<EventsDto.EventDetail>> detail(@PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(events.detail(eventId)));
    }

    // ----------------------------------------------------------------- writes

    @PostMapping
    @Operation(summary = "Create a contest or an examination",
        description = "Created as a draft: an event becomes visible to the people sitting it "
            + "the moment it stops being one, and saving a half-written examination must never "
            + "be the same click as publishing it to two hundred candidates.")
    public ResponseEntity<ApiResponse<EventsDto.EventDetail>> create(
        @AuthenticationPrincipal Long adminId,
        @Valid @RequestBody EventsDto.EventRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.status(201).body(
            ApiResponse.ok(events.create(adminId, req, httpReq)));
    }

    @PutMapping("/{eventId}")
    @Operation(summary = "Change an event's configuration")
    public ResponseEntity<ApiResponse<EventsDto.EventDetail>> update(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        @Valid @RequestBody EventsDto.EventRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(events.update(adminId, eventId, req, httpReq)));
    }

    @PutMapping("/{eventId}/lifecycle")
    @Operation(summary = "Publish a draft, end an event early, or archive a finished one")
    public ResponseEntity<ApiResponse<EventsDto.EventDetail>> lifecycle(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        @Valid @RequestBody EventsDto.LifecycleRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            events.setLifecycle(adminId, eventId, req.lifecycle(), httpReq)));
    }

    @DeleteMapping("/{eventId}")
    @Operation(summary = "Delete an event that nobody has sat",
        description = "Refused once there is a session log: that log is the record of something "
            + "that happened to people. Archive a finished event instead.")
    public ResponseEntity<ApiResponse<Void>> delete(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        HttpServletRequest httpReq) {
        events.delete(adminId, eventId, httpReq);
        return ResponseEntity.ok(ApiResponse.message("Deleted"));
    }

    // ------------------------------------------------------------ assignment

    @PostMapping("/{eventId}/assignments")
    @Operation(summary = "Assign teams or individual people to this event")
    public ResponseEntity<ApiResponse<EventsDto.EventDetail>> assign(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        @Valid @RequestBody EventsDto.AssignmentRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(events.assign(adminId, eventId, req, httpReq)));
    }

    @DeleteMapping("/{eventId}/assignments/teams/{teamId}")
    @Operation(summary = "Take a team off this event")
    public ResponseEntity<ApiResponse<EventsDto.EventDetail>> unassignTeam(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        @PathVariable Long teamId,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            events.unassignTeam(adminId, eventId, teamId, httpReq)));
    }

    @DeleteMapping("/{eventId}/assignments/users/{userId}")
    @Operation(summary = "Take one person off this event")
    public ResponseEntity<ApiResponse<EventsDto.EventDetail>> unassignUser(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        @PathVariable Long userId,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            events.unassignUser(adminId, eventId, userId, httpReq)));
    }

    // -------------------------------------------------------------- problems

    @PutMapping("/{eventId}/problems")
    @Operation(summary = "Set the problems, their order and what each is worth",
        description = "The whole list at once: ordering and marks are a set that has to stay "
            + "consistent, and four independent edits have intermediate states where two "
            + "problems are third.")
    public ResponseEntity<ApiResponse<List<EventsDto.ProblemRow>>> problems(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        @Valid @RequestBody EventsDto.ProblemsRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            events.setProblems(adminId, eventId, req, httpReq)));
    }

    // ------------------------------------------------------------ monitoring

    @GetMapping("/{eventId}/monitor")
    @Operation(summary = "Live monitoring for an examination in progress",
        description = "Who is present, who is away and for how long, what each candidate was "
            + "last seen doing. Every figure is an observation rather than a finding.")
    public ResponseEntity<ApiResponse<EventsDto.MonitorSnapshot>> monitor(
        @PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(monitor.snapshot(eventId)));
    }

    @GetMapping("/{eventId}/flags")
    @Operation(summary = "Suspicious activity in this examination",
        description = "Long or repeated absences, repeated sign-ins, very fast first submissions "
            + "and anything the system itself marked. Computed from the log on every read; "
            + "each entry is something worth a look, not a finding.")
    public ResponseEntity<ApiResponse<EventsDto.FlagReport>> flags(@PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(flags.flags(eventId)));
    }

    // ----------------------------------------------------------- leaderboard

    @GetMapping("/{eventId}/leaderboard")
    @Operation(summary = "This examination's leaderboard, whatever candidates are shown",
        description = "Ranked by problems solved, then total time: each solve timed from when "
            + "the accepted code was sent, plus the configured penalty per earlier wrong "
            + "attempt. The same snapshot candidates see, recomputed on its schedule.")
    public ResponseEntity<ApiResponse<EventsDto.Leaderboard>> leaderboard(
        @PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(leaderboard.forAdmin(eventId, false)));
    }

    @PostMapping("/{eventId}/leaderboard/refresh")
    @Operation(summary = "Recompute the leaderboard now, ahead of its schedule")
    public ResponseEntity<ApiResponse<EventsDto.Leaderboard>> refreshLeaderboard(
        @PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(leaderboard.forAdmin(eventId, true)));
    }

    @PutMapping("/{eventId}/leaderboard/settings")
    @Operation(summary = "Turn the leaderboard on or off, and set its refresh and penalty",
        description = "Can be changed at any time, including while the examination runs.")
    public ResponseEntity<ApiResponse<EventsDto.Leaderboard>> leaderboardSettings(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        @Valid @RequestBody EventsDto.LeaderboardSettings req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            leaderboard.updateSettings(adminId, eventId, req, httpReq)));
    }

    /**
     * The session log, filtered.
     *
     * The whole read is one call into the service, because the summary and the log have to come
     * from the same session — see {@link EventService#logs}.
     */
    @GetMapping("/{eventId}/logs")
    @Operation(summary = "Everything recorded during this event, filtered by person, team, "
        + "type or time")
    public ResponseEntity<ApiResponse<EventsDto.LogPage>> logs(
        @PathVariable Long eventId,
        @RequestParam(required = false) Long userId,
        @RequestParam(required = false) Long teamId,
        @RequestParam(required = false) String type,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
            events.logs(eventId, teamId, userId, type, from, to, page, size)));
    }

    /**
     * The languages an event may be restricted to.
     *
     * <p>Served rather than hard-coded in the page so that the list an admin picks from is the
     * same list the server validates against. Two copies would mean a language offered in the
     * console and refused on save, discovered by whoever was setting up a paper.
     *
     * <p>Wider than what the local runner can execute: an examination on DOMjudge may be
     * Java-only whatever this host has a compiler for, and the Run button being unavailable is
     * a smaller problem than not being able to set the rule.
     */
    @GetMapping("/languages")
    @Operation(summary = "Languages an event's submissions may be restricted to")
    public ResponseEntity<ApiResponse<List<Languages.Known>>> languageCatalog() {
        return ResponseEntity.ok(ApiResponse.ok(Languages.CATALOG));
    }

    // -------------------------------------------------- examination passwords
    //
    // The passwords that open a paper. Generated here, printed here, and never emailed
    // anywhere — see ExamPasswordService for why an examination password that arrives in a
    // mailbox defeats the arrangement it belongs to.
    //
    // Reading them is split from the rest of the detail screen deliberately: the status route
    // says whether passwords exist and is safe to load with the page, and the two reveal
    // routes hand over the secrets and are audited every time they are called. An admin who
    // opened the exam screen has not thereby read the room's codes.

    @GetMapping("/{eventId}/passwords")
    @Operation(summary = "Whether this examination has passwords, without saying what they are")
    public ResponseEntity<ApiResponse<EventsDto.PasswordStatus>> passwordStatus(
        @PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok(events.passwordStatus(eventId)));
    }

    @PostMapping("/{eventId}/passwords/exam")
    @Operation(summary = "Generate or rotate the password the invigilator gives the room")
    public ResponseEntity<ApiResponse<Map<String, String>>> generateExamPassword(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        HttpServletRequest httpReq) {
        String password = passwords.generateExamPassword(adminId, eventId, httpReq);
        return ResponseEntity.ok(ApiResponse.ok("Generated. Rotating it ends every session "
            + "opened with the previous one.", Map.of("password", password)));
    }

    @GetMapping("/{eventId}/passwords/exam")
    @Operation(summary = "Read the examination password back, to give it to the room")
    public ResponseEntity<ApiResponse<Map<String, String>>> revealExamPassword(
        @PathVariable Long eventId) {
        String password = passwords.revealExamPassword(eventId);
        return ResponseEntity.ok(ApiResponse.ok(password == null
            ? Map.of() : Map.of("password", password)));
    }

    @DeleteMapping("/{eventId}/passwords/exam")
    @Operation(summary = "Run this examination without a shared password")
    public ResponseEntity<ApiResponse<Void>> clearExamPassword(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        HttpServletRequest httpReq) {
        passwords.clearExamPassword(adminId, eventId, httpReq);
        return ResponseEntity.ok(ApiResponse.message(
            "This examination no longer asks for a shared password. Anyone already inside "
            + "stays inside."));
    }

    /**
     * Issues a personal code to every assigned candidate.
     *
     * <p>Additive unless {@code regenerate} is set, so a candidate added the morning of the
     * paper gets a slip without invalidating the two hundred already printed.
     */
    @PostMapping("/{eventId}/passwords/candidates")
    @Operation(summary = "Issue each assigned candidate their own code")
    public ResponseEntity<ApiResponse<List<EventsDto.IssuedPasscode>>> issuePasscodes(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        @RequestParam(defaultValue = "false") boolean regenerate,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            events.issuePasscodes(adminId, eventId, regenerate, httpReq)));
    }

    @GetMapping("/{eventId}/passwords/candidates")
    @Operation(summary = "Every candidate's code, for printing the desk slips")
    public ResponseEntity<ApiResponse<List<EventsDto.IssuedPasscode>>> revealPasscodes(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            events.revealPasscodes(adminId, eventId, httpReq)));
    }

    /** One candidate's code again, for the slip that went under a radiator at eleven. */
    @PostMapping("/{eventId}/passwords/candidates/{userId}")
    @Operation(summary = "Reissue one candidate's code")
    public ResponseEntity<ApiResponse<EventsDto.IssuedPasscode>> reissuePasscode(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        @PathVariable Long userId,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            events.reissuePasscode(adminId, eventId, userId, httpReq)));
    }

    @DeleteMapping("/{eventId}/passwords/candidates")
    @Operation(summary = "Withdraw every personal code on this examination")
    public ResponseEntity<ApiResponse<Void>> revokePasscodes(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long eventId,
        HttpServletRequest httpReq) {
        passwords.revokePasscodes(adminId, eventId, httpReq);
        return ResponseEntity.ok(ApiResponse.message(
            "Every personal code on this examination has been withdrawn."));
    }

    /**
     * Ends one candidate's access, so they have to unlock again.
     *
     * <p>For the candidate being moved to another machine, and for the one an invigilator is
     * removing from the room. It does not change their code — {@code reissuePasscode} does
     * that — so the ordinary use is "make them type it again, here".
     */
    @PostMapping("/{eventId}/access/{userId}/revoke")
    @Operation(summary = "Make one candidate unlock the examination again")
    public ResponseEntity<ApiResponse<Void>> revokeAccess(
        @PathVariable Long eventId,
        @PathVariable Long userId) {
        events.revokeAccess(eventId, userId);
        return ResponseEntity.ok(ApiResponse.message(
            "That candidate has to enter the examination password again."));
    }
}
