package com.cpintel.events;

import com.cpintel.archive.SubmissionArchive;
import com.cpintel.compete.CompeteDto;
import com.cpintel.compete.CompeteProvider;
import com.cpintel.compete.CompeteService;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.mongo.CodeSubmissionRepository;
import com.cpintel.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Rejudging one problem of an event.
 *
 * <p>For when a problem's tests or limits were wrong and have been put right on the judge.
 * Every submission made to that problem during the event is sent to the judge again — the code
 * CPIntel archived, as the contestant who wrote it, in the order it was first sent — and the
 * leaderboard is re-ranked on the verdicts that come back.
 *
 * <p><b>An attempt stays the attempt it was.</b> Nothing is added to the archive. Each row is
 * pointed at the judge's new submission and takes that one's verdict, keeping its own
 * {@code submittedAt} — so a solve still counts from when the contestant sent it, not from when
 * an admin pressed a button a day later, and a wrong attempt that is now right stops costing a
 * penalty. What it was before is kept on the row.
 *
 * <p>It runs in the background, like the export: sending a room's submissions and waiting for
 * the judge to work through them takes minutes. The event carries the state and, at the end, a
 * note saying how many were sent, how many could not be, and how many verdicts changed.
 *
 * <p>On the judge these are new submissions. Its own scoreboard counts them as further
 * attempts, and a contestant sees them in the judge's list; CPIntel's board is the one that
 * reads them as the originals.
 */
@Service
@Slf4j
public class EventRejudgeService {

    private final EventService events;
    private final GroupContestRepository eventRepository;
    private final ExamLeaderboardService boards;
    private final CompeteService compete;
    private final SubmissionArchive archive;
    private final CodeSubmissionRepository submissions;
    private final AuditService auditService;
    private final Executor executor;
    /** How often the judge is asked for the verdicts, and for how long before giving up. */
    private final Duration poll;
    private final Duration patience;

    @Autowired
    public EventRejudgeService(EventService events, GroupContestRepository eventRepository,
                               ExamLeaderboardService boards, CompeteService compete,
                               SubmissionArchive archive, CodeSubmissionRepository submissions,
                               AuditService auditService,
                               @Qualifier("syncTaskExecutor") Executor executor) {
        this(events, eventRepository, boards, compete, archive, submissions, auditService,
            executor, Duration.ofSeconds(10), Duration.ofMinutes(30));
    }

    EventRejudgeService(EventService events, GroupContestRepository eventRepository,
                        ExamLeaderboardService boards, CompeteService compete,
                        SubmissionArchive archive, CodeSubmissionRepository submissions,
                        AuditService auditService, Executor executor,
                        Duration poll, Duration patience) {
        this.events = events;
        this.eventRepository = eventRepository;
        this.boards = boards;
        this.compete = compete;
        this.archive = archive;
        this.submissions = submissions;
        this.auditService = auditService;
        this.executor = executor;
        this.poll = poll;
        this.patience = patience;
    }

    public EventsDto.EventDetail start(Long adminId, Long eventId, String rawLabel,
                                       HttpServletRequest httpReq) {
        GroupContest event = events.require(eventId);
        String label = rawLabel.trim().toUpperCase(Locale.ROOT);
        Instant now = Instant.now();

        if (!CompeteDto.Platform.DOMJUDGE.name().equals(event.getPlatform())) {
            throw ApiException.badRequest("Only a DOMjudge event can be rejudged: CPIntel "
                + "cannot send somebody's code to " + event.getPlatform() + " again.");
        }
        if (!event.hasStarted(now)) {
            throw ApiException.badRequest("This has not started, so there is nothing to rejudge.");
        }
        if (event.isCompleted()) {
            throw ApiException.badRequest("This is marked done, so its leaderboard is fixed. "
                + "Reopen it before rejudging a problem.");
        }
        if ("RUNNING".equals(event.rejudgeState(now))) {
            throw ApiException.badRequest("Problem " + event.getRejudgeLabel()
                + " is being rejudged. Wait for that to finish first.");
        }
        if (attempts(event, label).isEmpty()) {
            throw ApiException.badRequest(
                "Nobody submitted to problem " + label + " during this event.");
        }

        eventRepository.startRejudge(eventId, label, now);
        auditService.recordIn(event.getClassroomId(), adminId, AuditService.EVENT_REJUDGED,
            event.getKind(), eventId + ":" + label, httpReq);
        try {
            executor.execute(() -> run(eventId, label));
        } catch (RejectedExecutionException e) {
            eventRepository.finishRejudge(eventId, "FAILED",
                "The server was too busy to start the rejudge. Try again.");
        }
        return events.detail(eventId);
    }

    void run(Long eventId, String label) {
        try {
            GroupContest event = events.require(eventId);
            CompeteProvider provider = compete.provider(event.getPlatform());
            List<CodeSubmission> rows = attempts(event, label);

            List<String> sent = new ArrayList<>();
            Set<Long> people = new LinkedHashSet<>();
            int failed = 0;
            String firstFailure = null;
            for (CodeSubmission row : rows) {
                try {
                    String submissionId = provider.resubmit(row.getUserId(),
                        event.getExternalId(), label, row.getLanguageId(), row.getSource());
                    if (!archive.markResent(row.getId(), submissionId)) {
                        throw new IllegalStateException("the archive could not record it");
                    }
                    sent.add(row.getId());
                    people.add(row.getUserId());
                } catch (Exception e) {
                    failed++;
                    if (firstFailure == null) firstFailure = e.getMessage();
                    log.debug("Could not resend submission {} of user {} for event {}: {}",
                        row.getId(), row.getUserId(), eventId, e.getMessage());
                }
            }

            awaitVerdicts(event, provider, sent, people);
            boards.finalise(event);

            // Nothing reached the judge, so nothing was rejudged, whatever the reason.
            eventRepository.finishRejudge(eventId, sent.isEmpty() ? "FAILED" : "DONE",
                note(label, rows.size(), failed, firstFailure, submissions.findAllById(sent)));
        } catch (Exception e) {
            log.error("Rejudge of problem {} failed for event {}", label, eventId, e);
            eventRepository.finishRejudge(eventId, "FAILED", shorten("The rejudge stopped: "
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        }
    }

    /** The event's own attempts at one problem, oldest first — the order they are sent in. */
    private List<CodeSubmission> attempts(GroupContest event, String label) {
        return boards.attemptsOf(event).stream()
            .filter(row -> label.equalsIgnoreCase(row.getProblemIndex()))
            .filter(row -> row.getSource() != null && !row.getSource().isBlank())
            .sorted(Comparator.comparing(CodeSubmission::getSubmittedAt))
            .toList();
    }

    /**
     * Waits for the judge to work through what was sent.
     *
     * <p>Reading somebody's submissions as them is what moves a verdict into the archive, so
     * that is what is repeated — only for the people still waiting on one — until every row has
     * its verdict or patience runs out. A slow judge is not a failure: whatever is still being
     * judged then is said in the note, and the board picks it up when it is next recomputed.
     */
    private void awaitVerdicts(GroupContest event, CompeteProvider provider, List<String> sent,
                               Set<Long> people) throws InterruptedException {
        Instant deadline = Instant.now().plus(patience);
        Set<Long> waiting = people;
        while (!waiting.isEmpty()) {
            for (Long userId : waiting) {
                try {
                    provider.submissions(userId, event.getExternalId());
                } catch (Exception e) {
                    log.debug("Could not read verdicts for user {} on event {}: {}",
                        userId, event.getContestId(), e.getMessage());
                }
            }
            waiting = new LinkedHashSet<>();
            for (CodeSubmission row : submissions.findAllById(sent)) {
                if (ExamLeaderboardService.isPending(row.getVerdict())) waiting.add(row.getUserId());
            }
            if (waiting.isEmpty() || !Instant.now().isBefore(deadline)) return;
            Thread.sleep(poll.toMillis());
        }
    }

    private static String note(String label, int total, int failed, String firstFailure,
                               Iterable<CodeSubmission> resent) {
        int back = 0, changed = 0, pending = 0;
        for (CodeSubmission row : resent) {
            if (ExamLeaderboardService.isPending(row.getVerdict())) {
                pending++;
                continue;
            }
            back++;
            if (!row.getVerdict().equals(row.getVerdictBeforeRejudge())) changed++;
        }

        StringBuilder note = new StringBuilder("Problem ").append(label).append(": ")
            .append(total - failed).append(" of ").append(total)
            .append(total == 1 ? " submission" : " submissions")
            .append(" sent to the judge again. ");
        note.append(back).append(back == 1 ? " verdict is back, " : " verdicts are back, ")
            .append(changed).append(" changed.");
        if (pending > 0) {
            note.append(' ').append(pending).append(" still being judged — recompute the "
                + "leaderboard once the judge has caught up.");
        }
        if (failed > 0) {
            note.append(' ').append(failed).append(" could not be sent and keep their old "
                + "verdict").append(firstFailure == null ? "." : " (" + firstFailure + ").");
        }
        return shorten(note.toString());
    }

    private static String shorten(String text) {
        return text.length() > 500 ? text.substring(0, 497) + "..." : text;
    }
}
