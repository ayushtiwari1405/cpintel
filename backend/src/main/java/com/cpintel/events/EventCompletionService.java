package com.cpintel.events;

import com.cpintel.compete.CompeteService;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Closing an event for good, and handing over its record.
 *
 * <p>An event ends by its clock, but its results are not settled then: verdicts are still
 * landing, and a problem may be re-evaluated on the judge days later. So "done" is something an
 * admin says, once all of that is over. Saying it does three things, in this order:
 *
 * <ol>
 *   <li>every participant's verdicts are read from the judge one last time, which is what
 *       brings a re-evaluation into CPIntel's archive;</li>
 *   <li>the leaderboard is computed from them and fixed — it is not recomputed again;</li>
 *   <li>the submissions and that leaderboard are packed into a zip ({@link EventExport}) and
 *       kept for download.</li>
 * </ol>
 *
 * <p>That runs in the background. Reading two hundred people's submissions back from a judge
 * takes longer than a request may, so marking an event done answers at once and the event
 * carries the build's state — BUILDING, then READY or FAILED — for the page to follow.
 *
 * <p>Nothing here is final in the sense of irreversible: the export can be rebuilt after a
 * later re-evaluation, and the event can be reopened, which lets the board move again.
 */
@Service
@Slf4j
public class EventCompletionService {

    private final EventService events;
    private final GroupContestRepository eventRepository;
    private final ExamLeaderboardService boards;
    private final CompeteService compete;
    private final UserRepository userRepository;
    private final EventExportStore store;
    private final AuditService auditService;
    private final Executor executor;

    public EventCompletionService(EventService events, GroupContestRepository eventRepository,
                                  ExamLeaderboardService boards, CompeteService compete,
                                  UserRepository userRepository, EventExportStore store,
                                  AuditService auditService,
                                  @Qualifier("syncTaskExecutor") Executor executor) {
        this.events = events;
        this.eventRepository = eventRepository;
        this.boards = boards;
        this.compete = compete;
        this.userRepository = userRepository;
        this.store = store;
        this.auditService = auditService;
        this.executor = executor;
    }

    /** The export zip, ready to send. */
    public record Download(String fileName, Resource content, Long bytes) {}

    // ----------------------------------------------------------------- writes

    public EventsDto.EventDetail complete(Long adminId, Long eventId, HttpServletRequest httpReq) {
        GroupContest event = events.require(eventId);
        Instant now = Instant.now();
        GroupContest.Lifecycle stage = event.effectiveLifecycle(now);
        boolean over = event.getStartsAt() != null && event.getEndsAt() != null
            && !now.isBefore(event.getEndsAt())
            && (stage == GroupContest.Lifecycle.ENDED || stage == GroupContest.Lifecycle.ARCHIVED);
        if (!over) {
            throw ApiException.badRequest("This has not finished. An event is marked done once "
                + "it is over and nothing about its results is going to change.");
        }
        if (event.isCompleted()) {
            throw ApiException.badRequest("This is already marked done.");
        }

        start(eventId, now, adminId, now);
        auditService.recordIn(event.getClassroomId(), adminId, AuditService.EVENT_COMPLETED,
            event.getKind(), String.valueOf(eventId), httpReq);
        return events.detail(eventId);
    }

    /** Builds the export again — after a later re-evaluation, or after a build that failed. */
    public EventsDto.EventDetail rebuild(Long adminId, Long eventId, HttpServletRequest httpReq) {
        GroupContest event = requireCompleted(eventId);
        Instant now = Instant.now();
        requireNotBuilding(event, now);

        start(eventId, event.getCompletedAt(), event.getCompletedBy(), now);
        auditService.recordIn(event.getClassroomId(), adminId, AuditService.EVENT_COMPLETED,
            event.getKind(), eventId + ":rebuild", httpReq);
        return events.detail(eventId);
    }

    /** Takes the mark back: the board may move again, and the export is thrown away. */
    public EventsDto.EventDetail reopen(Long adminId, Long eventId, HttpServletRequest httpReq) {
        GroupContest event = requireCompleted(eventId);
        // A build still running would otherwise finish into an event that is no longer done.
        requireNotBuilding(event, Instant.now());

        eventRepository.clearCompletion(eventId);
        store.delete(event.getExportFileId());
        auditService.recordIn(event.getClassroomId(), adminId, AuditService.EVENT_REOPENED,
            event.getKind(), String.valueOf(eventId), httpReq);
        return events.detail(eventId);
    }

    // ------------------------------------------------------------------ reads

    public Download export(Long adminId, Long eventId, HttpServletRequest httpReq) {
        GroupContest event = requireCompleted(eventId);
        if (!"READY".equals(event.exportState(Instant.now())) || event.getExportFileId() == null) {
            throw ApiException.notFound("The export is not ready yet.");
        }
        Resource content = store.open(event.getExportFileId()).orElseThrow(() ->
            ApiException.notFound("The export file is missing. Build it again."));

        // Everybody's source code leaving the server is worth a line in the audit log.
        auditService.recordIn(event.getClassroomId(), adminId, AuditService.EVENT_EXPORTED,
            event.getKind(), String.valueOf(eventId), httpReq);
        return new Download(EventExport.fileName(event) + ".zip", content, event.getExportBytes());
    }

    /** The leaderboard as an admin sees it right now, as a spreadsheet. */
    public Download leaderboardWorkbook(Long eventId) {
        GroupContest event = events.require(eventId);
        EventsDto.LeaderboardStandings standings = boards.forAdmin(eventId, false).standings();
        if (standings == null) {
            throw ApiException.badRequest("There is no leaderboard until it has started.");
        }
        byte[] workbook = EventExport.leaderboardWorkbook(event, standings, Instant.now());
        return new Download(EventExport.fileName(event) + "-leaderboard.xlsx",
            new org.springframework.core.io.ByteArrayResource(workbook), (long) workbook.length);
    }

    // -------------------------------------------------------------- the build

    private void start(Long eventId, Instant completedAt, Long completedBy, Instant now) {
        eventRepository.startExport(eventId, completedAt, completedBy, now);
        try {
            executor.execute(() -> build(eventId));
        } catch (RejectedExecutionException e) {
            eventRepository.failExport(eventId,
                "The server was too busy to start building the export. Build it again.");
        }
    }

    void build(Long eventId) {
        try {
            GroupContest event = events.require(eventId);
            if (!event.isCompleted()) return;

            String unread = rereadVerdicts(event);
            EventsDto.LeaderboardStandings standings = boards.finalise(event);
            List<CodeSubmission> rows = boards.attemptsOf(event);

            Set<Long> ids = new LinkedHashSet<>();
            for (CodeSubmission row : rows) ids.add(row.getUserId());
            Map<Long, User> users = new HashMap<>();
            for (User user : userRepository.findAllById(ids)) users.put(user.getUserId(), user);

            String note = note(unread, standings.pendingSubmissions());
            byte[] zip = EventExport.zip(event, standings, rows, users, note, Instant.now());
            String fileId = store.save(EventExport.fileName(event) + ".zip", zip);

            if (eventRepository.storeExport(eventId, fileId, (long) zip.length, note) == 0) {
                store.delete(fileId);
                return;
            }
            // The one it replaces, when this was a rebuild.
            if (event.getExportFileId() != null) store.delete(event.getExportFileId());
        } catch (Exception e) {
            log.error("Could not build the export for event {}", eventId, e);
            eventRepository.failExport(eventId, shorten("The export could not be built: "
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        }
    }

    /**
     * Reads everybody's verdicts from the judge once more.
     *
     * <p>The archive learns a verdict when somebody's screen polls for it, and nobody's screen
     * is polling a contest that ended last week — so a submission re-evaluated since then still
     * carries its old verdict here. Reading each person's list as them moves the archive on,
     * through the same path their own screen used.
     *
     * @return what could not be re-read, in words, or null when everything was
     */
    private String rereadVerdicts(GroupContest event) {
        Set<Long> people = new LinkedHashSet<>();
        for (CodeSubmission row : boards.attemptsOf(event)) {
            if (row.getExternalId() != null) people.add(row.getUserId());
        }

        List<Long> failed = new ArrayList<>();
        for (Long userId : people) {
            try {
                // They have submissions on the judge, so an empty list is one it would not give.
                if (compete.provider(event.getPlatform())
                        .submissions(userId, event.getExternalId()).isEmpty()) {
                    failed.add(userId);
                }
            } catch (Exception e) {
                log.debug("Could not re-read verdicts for user {} on event {}: {}",
                    userId, event.getContestId(), e.getMessage());
                failed.add(userId);
            }
        }
        if (failed.isEmpty()) return null;
        return "The judge would not give the submissions of " + failed.size() + " of "
            + people.size() + " people, so their verdicts are as CPIntel last saw them.";
    }

    private static String note(String unread, int pending) {
        List<String> parts = new ArrayList<>();
        if (unread != null) parts.add(unread);
        if (pending > 0) {
            parts.add(pending + (pending == 1 ? " submission" : " submissions")
                + " had no verdict yet.");
        }
        return parts.isEmpty() ? null : shorten(String.join(" ", parts));
    }

    private static String shorten(String text) {
        return text.length() > 500 ? text.substring(0, 497) + "..." : text;
    }

    // ---------------------------------------------------------------- checks

    private GroupContest requireCompleted(Long eventId) {
        GroupContest event = events.require(eventId);
        if (!event.isCompleted()) {
            throw ApiException.badRequest("This has not been marked done.");
        }
        return event;
    }

    private static void requireNotBuilding(GroupContest event, Instant now) {
        if ("BUILDING".equals(event.exportState(now))) {
            throw ApiException.badRequest(
                "Its export is being built. Wait for that to finish first.");
        }
    }
}
