package com.cpintel.events;

import com.cpintel.common.Languages;
import com.cpintel.entity.ContestAssignment;
import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.ContestProblem;
import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.GroupStanding;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.repository.jpa.*;
import com.cpintel.repository.mongo.CodeSubmissionRepository;
import com.cpintel.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Contests and examinations: what they are, who may sit them, and where they are in their life.
 *
 * <p>One service for both kinds on purpose. Almost everything an examination needs — a window, a
 * roster, problems with marks against them, a place in a lifecycle — is what a contest needs,
 * and the handful of genuine differences are stated once here as rules rather than spread across
 * a duplicate implementation that would drift. The differences are:
 *
 * <ul>
 *   <li>An examination is never public. It is sat by named people or named teams, and an
 *       examination anybody could walk into is not an examination.</li>
 *   <li>An examination runs on DOMjudge, which is the judge the deployment owns. Codeforces
 *       cannot be asked to hold a contest open for one class at one time.</li>
 *   <li>An examination is monitored unless an admin deliberately turns that off, and it carries
 *       its own away-time threshold and desktop policy.</li>
 * </ul>
 *
 * <p>Participation is read from assignments rather than from team membership, which is what lets
 * an examination be given to three classes and four individuals at once. See
 * {@link GroupContestRepository#isAssignedTo} for the single query every path shares — there is
 * deliberately no second way of answering "may this person enter".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private static final int MAX_PROBLEMS = 60;

    private final GroupContestRepository eventRepository;
    private final ContestGroupRepository groupRepository;
    private final ContestAssignmentRepository assignmentRepository;
    private final ContestProblemRepository problemRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupStandingRepository standingRepository;
    private final ExamEventRepository examEventRepository;
    private final ExamPasscodeRepository passcodeRepository;
    private final CodeSubmissionRepository submissions;
    private final UserRepository userRepository;
    private final ExamEventService examEvents;
    private final ExamAccessService access;
    private final ExamPasswordService examPasswords;
    private final AuditService auditService;
    private final ObjectMapper json;

    // ------------------------------------------------------------ admin reads
    //
    // Every read here runs in a transaction, because an event's owning team is a lazy proxy and
    // the summary reads its name. Without one, each repository call gets its own session, the
    // proxy is detached by the time the mapper touches it, and the endpoint fails on exactly
    // the events that have a team — which is most of them.

    /** Every event of one kind, newest window first. */
    @Transactional(readOnly = true)
    public List<EventsDto.EventSummary> list(String kind) {
        Instant now = Instant.now();
        return eventRepository.findByKindOrderByStartsAtDesc(normaliseKind(kind)).stream()
            .map(event -> summarise(event, now))
            .toList();
    }

    @Transactional(readOnly = true)
    public EventsDto.EventDetail detail(Long eventId) {
        GroupContest event = require(eventId);
        Instant now = Instant.now();

        List<ContestAssignment> assignments = assignmentRepository.findByContest(eventId);
        List<EventsDto.AssignedTeam> teams = assignments.stream()
            .filter(a -> a.getGroup() != null)
            .map(a -> new EventsDto.AssignedTeam(
                a.getGroup().getGroupId(),
                a.getGroup().getName(),
                (int) memberRepository.countByGroupGroupId(a.getGroup().getGroupId())))
            .toList();
        List<EventsDto.AssignedUser> users = assignments.stream()
            .filter(a -> a.getUser() != null)
            .map(a -> new EventsDto.AssignedUser(
                a.getUser().getUserId(),
                a.getUser().getUsername(),
                a.getUser().getFullName(),
                Boolean.TRUE.equals(a.getUser().getIsActive())))
            .toList();

        List<EventsDto.ProblemRow> problems = problems(eventId);

        return new EventsDto.EventDetail(
            summarise(event, now),
            event.getRules(),
            EventMapper.policyOf(event, json),
            EventMapper.languagesOf(event),
            problems,
            teams,
            users);
    }

    @Transactional(readOnly = true)
    public List<EventsDto.ProblemRow> problems(Long eventId) {
        return problemRepository.findByContestContestIdOrderByOrderingAscLabelAsc(eventId).stream()
            .map(EventMapper::toProblem)
            .toList();
    }

    /** Everyone who may enter, teams expanded and duplicates collapsed. */
    @Transactional(readOnly = true)
    public Set<Long> participantIds(Long eventId) {
        Set<Long> ids = new LinkedHashSet<>(assignmentRepository.participantsByTeam(eventId));
        ids.addAll(assignmentRepository.participantsNamedDirectly(eventId));
        return ids;
    }

    @Transactional(readOnly = true)
    public EventsDto.EventSummary summarise(GroupContest event, Instant now) {
        Long id = event.getContestId();
        List<ContestAssignment> assignments = assignmentRepository.findByContest(id);
        int teams = (int) assignments.stream().filter(a -> a.getGroup() != null).count();
        int named = (int) assignments.stream().filter(a -> a.getUser() != null).count();
        return EventMapper.toSummary(event, now, teams, named,
            participantIds(id).size(),
            problemRepository.findByContestContestIdOrderByOrderingAscLabelAsc(id).size());
    }

    /**
     * The session log for one event, filtered the way an invigilator reads it.
     *
     * <p>Assembled here rather than in the controller because it needs the event's summary and
     * its log in the same breath — and the summary reads the owning team, which is a lazy
     * proxy. Loading the event in one session and mapping it in another is exactly the bug this
     * shape prevents.
     *
     * <p>A team filter is resolved to its members here too, which is what makes "this class,
     * and these three individuals as well" expressible at all: the log knows about people, and
     * a team is a set of them.
     */
    @Transactional(readOnly = true)
    public EventsDto.LogPage logs(Long eventId, Long teamId, Long userId, String type,
                                  Instant from, Instant to, int page, int size) {
        GroupContest event = require(eventId);

        List<Long> userIds = null;
        if (teamId != null) {
            userIds = memberRepository.findByGroup(teamId).stream()
                .map(member -> member.getUser().getUserId())
                .toList();
            // A team with nobody in it filters to nobody rather than to everybody: an empty
            // list would otherwise read as "no filter" and show the whole examination.
            if (userIds.isEmpty()) userIds = List.of(-1L);
        }
        if (userId != null) userIds = List.of(userId);

        EventsDto.LogPage found = examEvents.search(event, userIds, type, from, to, page, size);
        return new EventsDto.LogPage(
            summarise(event, Instant.now()),
            found.entries(), found.page(), found.size(), found.total(), found.totalPages(),
            found.types(), found.retentionDays());
    }

    // ----------------------------------------------------------- admin writes

    @Transactional
    public EventsDto.EventDetail create(Long adminId, EventsDto.EventRequest req,
                                        HttpServletRequest httpReq) {
        String kind = normaliseKind(req.kind());
        String platform = normalisePlatform(req.platform(), kind);
        requireWindowOrder(req.startsAt(), req.endsAt());

        ContestGroup owner = req.teamId() == null ? null : requireTeam(req.teamId());

        GroupContest event = GroupContest.builder()
            .kind(kind)
            .group(owner)
            .platform(platform)
            .externalId(req.externalId().trim())
            .name(req.name().trim())
            .description(trimToNull(req.description()))
            .rules(trimToNull(req.rules()))
            .url(trimToNull(req.url()))
            .startsAt(req.startsAt())
            .endsAt(req.endsAt())
            // Created as a draft unless the caller asked otherwise. An event appears to the
            // people sitting it the moment it is not a draft, and "saved half-written" must
            // never be the same click as "published to two hundred candidates".
            .lifecycle(GroupContest.Lifecycle.DRAFT.name())
            .visibility(normaliseVisibility(req.visibility(), kind))
            .lockdownRequired(lockdownFor(req.lockdownRequired(), kind))
            .awayThresholdSeconds(normaliseThreshold(req.awayThresholdSeconds()))
            .desktopPolicy(writePolicy(req.desktopPolicy(), kind))
            .allowedLanguages(joinLanguages(req.allowedLanguages()))
            .build();

        event = eventRepository.save(event);

        if (owner != null) assignTeam(event, owner, adminId);
        applyAssignments(event, req.teamIds(), req.userIds(), adminId);
        if (req.problems() != null && !req.problems().isEmpty()) {
            replaceProblems(event, req.problems());
        }

        auditService.record(adminId, AuditService.EVENT_CREATED, kind,
            event.getContestId() + ":" + event.getName(), httpReq);
        return detail(event.getContestId());
    }

    @Transactional
    public EventsDto.EventDetail update(Long adminId, Long eventId, EventsDto.EventRequest req,
                                        HttpServletRequest httpReq) {
        GroupContest event = require(eventId);
        String kind = normaliseKind(req.kind());
        requireWindowOrder(req.startsAt(), req.endsAt());

        event.setKind(kind);
        event.setPlatform(normalisePlatform(req.platform(), kind));
        event.setExternalId(req.externalId().trim());
        event.setName(req.name().trim());
        event.setDescription(trimToNull(req.description()));
        event.setRules(trimToNull(req.rules()));
        event.setUrl(trimToNull(req.url()));
        event.setStartsAt(req.startsAt());
        event.setEndsAt(req.endsAt());
        event.setVisibility(normaliseVisibility(req.visibility(), kind));
        event.setLockdownRequired(lockdownFor(req.lockdownRequired(), kind));
        event.setAwayThresholdSeconds(normaliseThreshold(req.awayThresholdSeconds()));
        event.setAllowedLanguages(joinLanguages(req.allowedLanguages()));
        if (req.desktopPolicy() != null) {
            event.setDesktopPolicy(writePolicy(req.desktopPolicy(), kind));
        }
        if (req.teamId() != null) {
            ContestGroup owner = requireTeam(req.teamId());
            event.setGroup(owner);
            assignTeam(event, owner, adminId);
        }

        eventRepository.save(event);

        if (req.problems() != null) replaceProblems(event, req.problems());
        applyAssignments(event, req.teamIds(), req.userIds(), adminId);

        auditService.record(adminId, AuditService.EVENT_UPDATED, kind,
            String.valueOf(eventId), httpReq);
        return detail(eventId);
    }

    /**
     * Moves an event along its lifecycle.
     *
     * <p>Only the two stored states can be set by hand. SCHEDULED, ACTIVE and ENDED are read
     * from the clock, so "start it now" and "end it now" are expressed by moving the window
     * rather than by setting a flag the clock would immediately contradict — except for ending
     * early, which sets the end time to now and is what an invigilator actually means by
     * "stop".
     */
    @Transactional
    public EventsDto.EventDetail setLifecycle(Long adminId, Long eventId, String requested,
                                              HttpServletRequest httpReq) {
        GroupContest event = require(eventId);
        GroupContest.Lifecycle target = parseLifecycle(requested);
        Instant now = Instant.now();
        GroupContest.Lifecycle current = event.effectiveLifecycle(now);

        switch (target) {
            case DRAFT -> {
                if (current == GroupContest.Lifecycle.ACTIVE) {
                    throw ApiException.badRequest(
                        "This is running. End it before taking it back to a draft — pulling it "
                            + "out from under the people sitting it would lose their session.");
                }
                event.setLifecycle(GroupContest.Lifecycle.DRAFT.name());
                event.setArchivedAt(null);
            }
            case SCHEDULED -> {
                // Publishing. The clock takes over from here: it becomes active when its window
                // opens, without anybody having to remember to press anything.
                if (event.getStartsAt() == null || event.getEndsAt() == null) {
                    throw ApiException.badRequest(
                        "Give it a start and an end before publishing it.");
                }
                event.setLifecycle(GroupContest.Lifecycle.SCHEDULED.name());
                event.setArchivedAt(null);
            }
            case ENDED -> {
                // Ending early. The window is what everything else reads, so it is the window
                // that moves; leaving it in the future and setting a flag would mean the arena
                // and this screen disagreed about whether submissions were open.
                if (event.getEndsAt() == null || event.getEndsAt().isAfter(now)) {
                    event.setEndsAt(now);
                }
                event.setLifecycle(GroupContest.Lifecycle.SCHEDULED.name());
                event.setArchivedAt(null);
            }
            case ARCHIVED -> {
                if (current == GroupContest.Lifecycle.ACTIVE) {
                    throw ApiException.badRequest("This is still running. End it first.");
                }
                event.setLifecycle(GroupContest.Lifecycle.ARCHIVED.name());
                event.setArchivedAt(now);
            }
            case ACTIVE -> throw ApiException.badRequest(
                "An event becomes active when its window opens. Move its start time instead.");
        }

        eventRepository.save(event);
        auditService.record(adminId, AuditService.EVENT_LIFECYCLE, event.getKind(),
            eventId + ":" + target.name(), httpReq);
        return detail(eventId);
    }

    /**
     * Deletes an event and everything recorded about it.
     *
     * Refused while it is running, and refused once it has been sat: standings, the violation
     * trail and the examination log are the record of something that actually happened to
     * people, and no confirmation dialog makes destroying them recoverable. Archiving is the
     * way to make a finished event go away.
     */
    @Transactional
    public void delete(Long adminId, Long eventId, HttpServletRequest httpReq) {
        GroupContest event = require(eventId);
        Instant now = Instant.now();

        if (event.effectiveLifecycle(now) == GroupContest.Lifecycle.ACTIVE) {
            throw ApiException.badRequest("This is running. End it before deleting it.");
        }
        if (examEventRepository.countByContestContestId(eventId) > 0) {
            throw ApiException.badRequest(
                "People have already sat this — deleting it would take their session log with "
                    + "it. Archive it instead.");
        }

        auditService.record(adminId, AuditService.EVENT_DELETED, event.getKind(),
            eventId + ":" + event.getName(), httpReq);
        eventRepository.delete(event);
    }

    // ------------------------------------------------------------ assignment

    @Transactional
    public EventsDto.EventDetail assign(Long adminId, Long eventId,
                                        EventsDto.AssignmentRequest req,
                                        HttpServletRequest httpReq) {
        GroupContest event = require(eventId);
        int added = applyAssignments(event, req.teamIds(), req.userIds(), adminId);

        if (added > 0) {
            auditService.record(adminId, AuditService.EVENT_ASSIGNED, event.getKind(),
                eventId + ":+" + added, httpReq);
        }
        return detail(eventId);
    }

    @Transactional
    public EventsDto.EventDetail unassignTeam(Long adminId, Long eventId, Long teamId,
                                              HttpServletRequest httpReq) {
        GroupContest event = require(eventId);
        assignmentRepository.deleteByContestContestIdAndGroupGroupId(eventId, teamId);
        // The owning team is also the one the by-team screens hang off, so removing it as a
        // participant has to remove it as the owner too or the event would still claim it.
        if (event.getGroup() != null && event.getGroup().getGroupId().equals(teamId)) {
            event.setGroup(null);
            eventRepository.save(event);
        }
        auditService.record(adminId, AuditService.EVENT_UNASSIGNED, event.getKind(),
            eventId + ":team:" + teamId, httpReq);
        return detail(eventId);
    }

    @Transactional
    public EventsDto.EventDetail unassignUser(Long adminId, Long eventId, Long userId,
                                              HttpServletRequest httpReq) {
        GroupContest event = require(eventId);
        assignmentRepository.deleteByContestContestIdAndUserUserId(eventId, userId);
        auditService.record(adminId, AuditService.EVENT_UNASSIGNED, event.getKind(),
            eventId + ":user:" + userId, httpReq);
        return detail(eventId);
    }

    // -------------------------------------------------------------- problems

    @Transactional
    public List<EventsDto.ProblemRow> setProblems(Long adminId, Long eventId,
                                                  EventsDto.ProblemsRequest req,
                                                  HttpServletRequest httpReq) {
        GroupContest event = require(eventId);
        replaceProblems(event, req.problems());
        auditService.record(adminId, AuditService.EVENT_PROBLEMS, event.getKind(),
            eventId + ":" + (req.problems() == null ? 0 : req.problems().size()), httpReq);
        return problems(eventId);
    }

    // ------------------------------------------------------ candidate's view

    /** The events of one kind this person may sit, drafts and archives excluded. */
    @Transactional(readOnly = true)
    public List<EventsDto.EventSummary> mine(Long userId, String kind) {
        String wanted = normaliseKind(kind);
        Instant now = Instant.now();
        return eventRepository.findAllForParticipant(userId).stream()
            .filter(event -> wanted.equals(event.getKind()))
            .filter(event -> event.effectiveLifecycle(now) != GroupContest.Lifecycle.ARCHIVED)
            .map(event -> summarise(event, now))
            .toList();
    }

    /**
     * One examination, as the person sitting it sees it.
     *
     * Their own clock, their own problems, their own place — and the two things they are owed
     * before being watched: that they are, and how long they may be away before it is recorded.
     */
    @Transactional(readOnly = true)
    public EventsDto.MyExam myExam(Long userId, Long eventId) {
        GroupContest event = requireAssigned(userId, eventId);
        Instant now = Instant.now();

        long untilStart = event.getStartsAt() == null
            ? 0 : event.getStartsAt().getEpochSecond() - now.getEpochSecond();
        long remaining = event.getEndsAt() == null
            ? 0 : Math.max(0, event.getEndsAt().getEpochSecond() - now.getEpochSecond());

        Map<String, Integer> counts = examEvents.countsFor(eventId, userId);
        GroupStanding mine = standingRepository.findByContest(eventId).stream()
            .filter(row -> row.getUser().getUserId().equals(userId))
            .findFirst().orElse(null);

        boolean requiresPassword = access.requiresUnlock(event, userId);
        boolean unlocked = access.isUnlocked(event, userId);

        GroupContest.Lifecycle stage = event.effectiveLifecycle(now);

        /*
         * The problem list is withheld until the paper is unlocked.
         *
         * Labels and marks are not the questions — those live on the judge — but they are the
         * shape of the paper, and handing them to somebody who has not been given the room's
         * password would make the lock decorative. The clock, the rules and the fact that a
         * password is wanted are all still returned, because a candidate sitting in front of a
         * locked paper needs to know what they are waiting for.
         */
        boolean mayReadPaper = unlocked || stage != GroupContest.Lifecycle.ACTIVE;

        return new EventsDto.MyExam(
            summarise(event, now),
            event.getRules(),
            mayReadPaper ? problems(eventId) : List.of(),
            EventMapper.policyOf(event, json),
            EventMapper.languagesOf(event),
            untilStart,
            remaining,
            counts.getOrDefault(ExamEvent.Type.EXAM_ENTERED.name(), 0) > 0,
            counts.getOrDefault(ExamEvent.Type.PROBLEM_SUBMITTED.name(), 0),
            mine == null ? null : mine.getGroupRank(),
            mine == null ? null : mine.getSolved(),
            requiresPassword,
            unlocked,
            examPasswords.requiresExamPassword(event),
            examPasswords.requiresPasscode(eventId, userId),
            // Reading your own code back belongs to a paper that is over. During one it would
            // be a second window on the same work, and there is nothing to recover: the editor
            // still has it.
            stage == GroupContest.Lifecycle.ENDED || stage == GroupContest.Lifecycle.ARCHIVED);
    }

    /**
     * Records that this candidate has opened the examination, and hands back its detail.
     *
     * Entering a draft or an archived examination is impossible because they are not assigned
     * to anybody; entering one that has not started is allowed — that is a candidate sitting
     * and waiting, which is what they are told to do — and only produces a log entry once the
     * window is open.
     */
    @Transactional
    public EventsDto.MyExam enter(Long userId, Long eventId) {
        GroupContest event = requireAssigned(userId, eventId);
        if (event.isOpenForParticipation(Instant.now())) {
            // Entering a live paper that has not been unlocked is not an error — it is what a
            // candidate does before they are handed their slip, and refusing it would leave
            // them with nowhere to stand while they wait. It simply is not recorded as having
            // entered, because they have not.
            if (access.isUnlocked(event, userId)) {
                examEvents.recordServerSide(event, userId, ExamEvent.Type.EXAM_ENTERED, null,
                    "Entered the examination");
            }
        }
        return myExam(userId, eventId);
    }

    /**
     * Opens a live examination with the passwords handed out in the room.
     *
     * <p>Assignment is checked first and answers 404 for a paper that is not this person's,
     * exactly as every other candidate route does — so a wrong password on somebody else's
     * examination cannot be told apart from a wrong password on one that does not exist.
     */
    @Transactional
    public EventsDto.MyExam unlock(Long userId, Long eventId, EventsDto.UnlockRequest req,
                                   HttpServletRequest httpReq) {
        GroupContest event = requireAssigned(userId, eventId);
        if (!event.isExam()) {
            throw ApiException.badRequest("This is a contest. It does not ask for a password.");
        }
        access.unlock(event, userId,
            req == null ? null : req.examPassword(),
            req == null ? null : req.passcode(),
            httpReq);

        examEvents.recordServerSide(event, userId, ExamEvent.Type.EXAM_ENTERED, null,
            "Entered the examination");
        return myExam(userId, eventId);
    }

    // ------------------------------------------------ reading your own work back

    /**
     * What this candidate submitted into a past examination.
     *
     * <p>Read out of the submission archive, which is written <em>before</em> each submission
     * leaves for the judge — so a paper where the judge went down halfway still has every
     * attempt in it, including the ones that never landed. For a DOMjudge examination this
     * archive is the only readable record there is: unlike Codeforces there is no public
     * submission page to re-fetch a source from, which is exactly why the write happens first.
     *
     * <p><b>Their own code, and nothing more.</b> No marks, no verdicts belonging to anybody
     * else, no test data. Results are published by whoever decides to publish them, and a
     * review screen that quietly became a results screen would take that decision away from
     * them.
     */
    @Transactional(readOnly = true)
    public List<EventsDto.MySubmission> mySubmissions(Long userId, Long eventId) {
        GroupContest event = requireReviewable(userId, eventId);

        Map<String, String> titles = new java.util.HashMap<>();
        for (ContestProblem problem : problemRepository.findByContestContestIdOrderByOrderingAscLabelAsc(eventId)) {
            if (problem.getLabel() != null) {
                titles.put(problem.getLabel().toUpperCase(Locale.ROOT), problem.getTitle());
            }
        }

        return submissions
            .findByUserIdAndPlatformAndContestId(userId, event.getPlatform(), event.getExternalId())
            .stream()
            .sorted(Comparator.comparing(CodeSubmission::getSubmittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .map(row -> new EventsDto.MySubmission(
                row.getId(),
                row.getExternalId(),
                row.getProblemIndex(),
                row.getProblemName() != null ? row.getProblemName()
                    : titles.get(row.getProblemIndex() == null ? ""
                        : row.getProblemIndex().toUpperCase(Locale.ROOT)),
                row.getLanguageId(),
                row.getLanguageLabel(),
                row.getVerdict(),
                row.getSubmittedAt(),
                row.getSourceBytes(),
                // The list never carries source. A paper with sixty attempts on it would
                // otherwise be a megabyte of code nobody asked to read yet.
                null))
            .toList();
    }

    /** One of those submissions, with the code, to read back into an editor. */
    @Transactional(readOnly = true)
    public EventsDto.MySubmission mySubmission(Long userId, Long eventId, String submissionId) {
        GroupContest event = requireReviewable(userId, eventId);

        CodeSubmission row = submissions.findById(submissionId)
            .filter(r -> userId.equals(r.getUserId()))
            // Belonging to this examination is checked as well as belonging to this person.
            // Without it the route would read any archived submission of the caller's through
            // an examination they happen to have sat, which is a different feature that nobody
            // asked for and that the ended-paper rule would not bound.
            .filter(r -> event.getPlatform().equals(r.getPlatform()))
            .filter(r -> event.getExternalId().equals(r.getContestId()))
            .orElseThrow(() -> ApiException.notFound(
                "No submission of yours with that id on this examination."));

        return new EventsDto.MySubmission(row.getId(), row.getExternalId(),
            row.getProblemIndex(), row.getProblemName(), row.getLanguageId(),
            row.getLanguageLabel(), row.getVerdict(), row.getSubmittedAt(),
            row.getSourceBytes(), row.getSource());
    }

    /**
     * The examination, if this person may read their own work back from it.
     *
     * <p>Assigned, and finished. The second half is the interesting one: during a live paper
     * there is nothing to recover — the editor still holds the code — and a second window onto
     * the same submissions inside a monitored sitting is a surface with no purpose and an
     * obvious misuse. Archived papers stay readable, because an archive is for the record
     * rather than a way of taking it back.
     */
    private GroupContest requireReviewable(Long userId, Long eventId) {
        GroupContest event = requireAssigned(userId, eventId);
        GroupContest.Lifecycle stage = event.effectiveLifecycle(Instant.now());
        if (stage != GroupContest.Lifecycle.ENDED && stage != GroupContest.Lifecycle.ARCHIVED) {
            throw ApiException.forbidden(
                "You can read your own code back once this examination has ended.");
        }
        return event;
    }

    // -------------------------------------------------- examination passwords

    /**
     * How this examination's passwords stand, without saying what any of them are.
     *
     * Safe to render with the rest of the admin screen. Reading the passwords themselves is a
     * separate request that is audited, because it is an action somebody took rather than a
     * page that happened to load.
     */
    @Transactional(readOnly = true)
    public EventsDto.PasswordStatus passwordStatus(Long eventId) {
        GroupContest event = require(eventId);
        String setBy = null;
        if (event.getExamPasswordSetBy() != null) {
            setBy = event.getExamPasswordSetBy().getUsername();
        }
        return new EventsDto.PasswordStatus(
            examPasswords.isConfigured(),
            event.getExamPassword() != null,
            event.getExamPasswordSetAt(),
            setBy,
            event.getExamPasswordGen() == null ? 0 : event.getExamPasswordGen(),
            (int) passcodeRepository.countForContest(eventId),
            participantIds(eventId).size());
    }

    @Transactional
    public List<EventsDto.IssuedPasscode> issuePasscodes(Long adminId, Long eventId,
                                                         boolean regenerate,
                                                         HttpServletRequest httpReq) {
        // Read through the assignment list rather than from a roster column, so a team added
        // to the examination this morning brings its members with it.
        Set<Long> participants = participantIds(eventId);
        return examPasswords.issuePasscodes(adminId, eventId, participants, regenerate, httpReq)
            .stream().map(EventService::toIssuedDto).toList();
    }

    @Transactional(readOnly = true)
    public List<EventsDto.IssuedPasscode> revealPasscodes(Long adminId, Long eventId,
                                                          HttpServletRequest httpReq) {
        return examPasswords.revealPasscodes(adminId, eventId, httpReq)
            .stream().map(EventService::toIssuedDto).toList();
    }

    @Transactional
    public EventsDto.IssuedPasscode reissuePasscode(Long adminId, Long eventId, Long userId,
                                                    HttpServletRequest httpReq) {
        if (!eventRepository.isAssignedTo(eventId, userId)) {
            throw ApiException.badRequest(
                "That person is not assigned to this examination, so a code would open "
                + "nothing. Assign them first.");
        }
        return toIssuedDto(examPasswords.reissue(adminId, eventId, userId, httpReq));
    }

    /** Makes one candidate unlock again — for a move to another machine, or a removal. */
    public void revokeAccess(Long eventId, Long userId) {
        access.revoke(eventId, userId);
    }

    private static EventsDto.IssuedPasscode toIssuedDto(ExamPasswordService.Issued issued) {
        return new EventsDto.IssuedPasscode(issued.userId(), issued.username(),
            issued.fullName(), issued.code(), issued.issuedAt(), issued.firstUsedAt(),
            issued.useCount());
    }

    // ---------------------------------------------------------- shared checks

    public GroupContest require(Long eventId) {
        return eventRepository.findById(eventId)
            .orElseThrow(() -> ApiException.notFound("No such contest or examination"));
    }

    /**
     * The event, if this person may sit it.
     *
     * Deliberately the same 404 for "no such event" and "not yours". Which of the two it is
     * would tell a candidate that an examination exists and they were left off it, which is a
     * conversation for their invigilator rather than for a probe of the API.
     */
    public GroupContest requireAssigned(Long userId, Long eventId) {
        if (!eventRepository.isAssignedTo(eventId, userId)) {
            throw ApiException.notFound("No such contest or examination");
        }
        return require(eventId);
    }

    // --------------------------------------------------------------- helpers

    private int applyAssignments(GroupContest event, List<Long> teamIds, List<Long> userIds,
                                 Long adminId) {
        int added = 0;
        if (teamIds != null) {
            for (Long teamId : new HashSet<>(teamIds)) {
                if (teamId == null) continue;
                if (assignTeam(event, requireTeam(teamId), adminId)) added++;
            }
        }
        if (userIds != null) {
            for (Long userId : new HashSet<>(userIds)) {
                if (userId == null) continue;
                User user = userRepository.findById(userId)
                    .orElseThrow(() -> ApiException.notFound("No such user"));
                if (assignmentRepository.existsByContestContestIdAndUserUserId(
                        event.getContestId(), userId)) {
                    continue;
                }
                assignmentRepository.save(ContestAssignment.builder()
                    .contest(event).user(user).assignedBy(adminId).build());
                added++;
            }
        }
        return added;
    }

    private boolean assignTeam(GroupContest event, ContestGroup team, Long adminId) {
        if (assignmentRepository.existsByContestContestIdAndGroupGroupId(
                event.getContestId(), team.getGroupId())) {
            return false;
        }
        assignmentRepository.save(ContestAssignment.builder()
            .contest(event).group(team).assignedBy(adminId).build());
        return true;
    }

    /**
     * Replaces the problem list wholesale.
     *
     * A whole-list write rather than per-problem edits, because ordering and marks are a set
     * that has to be consistent: reordering four problems as four independent updates has
     * intermediate states where two of them are third.
     */
    private void replaceProblems(GroupContest event, List<EventsDto.ProblemRequest> requested) {
        List<EventsDto.ProblemRequest> problems = requested == null ? List.of() : requested;
        if (problems.size() > MAX_PROBLEMS) {
            throw ApiException.badRequest(
                "That is more than " + MAX_PROBLEMS + " problems — check the list.");
        }

        Set<String> seen = new HashSet<>();
        for (EventsDto.ProblemRequest problem : problems) {
            String label = problem.label() == null ? "" : problem.label().trim();
            if (label.isEmpty()) throw ApiException.badRequest("Every problem needs a label.");
            if (!seen.add(label.toUpperCase(Locale.ROOT))) {
                throw ApiException.badRequest("Problem " + label + " is listed twice.");
            }
        }

        problemRepository.deleteAll(
            problemRepository.findByContestContestIdOrderByOrderingAscLabelAsc(
                event.getContestId()));
        problemRepository.flush();

        int order = 0;
        List<ContestProblem> rows = new ArrayList<>(problems.size());
        for (EventsDto.ProblemRequest problem : problems) {
            rows.add(ContestProblem.builder()
                .contest(event)
                .label(problem.label().trim())
                .title(trimToNull(problem.title()))
                .externalId(trimToNull(problem.externalId()))
                .ordering(problem.ordering() == null ? order : problem.ordering())
                .points(problem.points() == null ? null : BigDecimal.valueOf(problem.points()))
                .build());
            order++;
        }
        rows.sort(Comparator.comparing(ContestProblem::getOrdering));
        problemRepository.saveAll(rows);
    }

    private ContestGroup requireTeam(Long teamId) {
        return groupRepository.findById(teamId)
            .orElseThrow(() -> ApiException.notFound("No such team"));
    }

    private String normaliseKind(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        try {
            return GroupContest.Kind.valueOf(value).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Kind must be CONTEST or EXAM");
        }
    }

    /**
     * An examination runs on the judge the deployment owns.
     *
     * Codeforces cannot be asked to hold a contest open for one class at one time, and an
     * examination whose window belongs to somebody else's site is not one CPIntel can invigilate.
     */
    private String normalisePlatform(String raw, String kind) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        String platform;
        try {
            platform = GroupContest.Platform.valueOf(value).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Platform must be CODEFORCES or DOMJUDGE");
        }
        if (GroupContest.Kind.EXAM.name().equals(kind)
            && !GroupContest.Platform.DOMJUDGE.name().equals(platform)) {
            throw ApiException.badRequest(
                "An examination runs on DOMjudge — that is the judge this deployment controls.");
        }
        return platform;
    }

    private String normaliseVisibility(String raw, String kind) {
        if (GroupContest.Kind.EXAM.name().equals(kind)) {
            // An examination anybody could walk into is not an examination. Rather than refuse
            // a request that merely left the field at its default, the rule is applied.
            String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            return GroupContest.Visibility.USERS.name().equals(value)
                ? GroupContest.Visibility.USERS.name()
                : GroupContest.Visibility.TEAMS.name();
        }
        if (raw == null || raw.isBlank()) return GroupContest.Visibility.TEAMS.name();
        try {
            return GroupContest.Visibility.valueOf(raw.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Visibility must be PUBLIC, TEAMS or USERS");
        }
    }

    /**
     * Only an examination is monitored, and it is monitored unless an admin says otherwise.
     *
     * <p><b>A contest is never monitored, whatever the request asks for.</b> That is a product
     * decision rather than a default, which is why it ignores {@code requested} rather than
     * merely defaulting it to false. Monitoring is the thing that makes an examination an
     * examination — being watched, being told you are watched, and leaving a session log
     * somebody reads afterwards. A contest is practice among people who chose to enter it, and
     * watching them buys nothing anybody asked for while making every round feel like a
     * proctored one.
     *
     * <p>The practical half of this matters as much as the principle: with contests ungated,
     * {@link com.cpintel.groups.ProctoringGate} has nothing to do on the ordinary path, and
     * the one code path that can refuse a submission for want of a heartbeat is reached only
     * by papers whose admin deliberately asked for it.
     */
    private boolean lockdownFor(Boolean requested, String kind) {
        if (!GroupContest.Kind.EXAM.name().equals(kind)) return false;
        return requested == null || requested;
    }

    private int normaliseThreshold(Integer seconds) {
        if (seconds == null) return 10;
        if (seconds < 1 || seconds > 3600) {
            throw ApiException.badRequest(
                "The away threshold has to be between 1 second and an hour.");
        }
        return seconds;
    }

    private String writePolicy(EventsDto.DesktopPolicy policy, String kind) {
        EventsDto.DesktopPolicy effective = policy != null ? policy
            : GroupContest.Kind.EXAM.name().equals(kind)
                ? EventsDto.DesktopPolicy.examDefault()
                : EventsDto.DesktopPolicy.contestDefault();
        try {
            return json.writeValueAsString(effective);
        } catch (Exception e) {
            // Six booleans cannot fail to serialise; if they somehow do, the event keeps the
            // default rather than an empty policy that would restrict nothing.
            log.warn("Could not store desktop policy: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The languages an event accepts, checked against the catalogue before they are stored.
     *
     * <p>Validated rather than trusted because of how this fails if it is not. A list is a
     * restriction, so an id nobody recognises does not make one language unavailable — it makes
     * that entry match nothing, and a list of three where two are typos silently narrows the
     * paper to one language. That is discovered by a room full of people at the moment they try
     * to pick a compiler, which is the worst possible time and place.
     *
     * <p>Empty means no restriction, which is the default and is a real answer rather than a
     * missing one: most examinations take whatever the judge takes.
     */
    private String joinLanguages(List<String> languages) {
        if (languages == null || languages.isEmpty()) return null;

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String raw : languages) {
            if (!StringUtils.hasText(raw)) continue;
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (Languages.isKnown(id)) ids.add(id);
            else unknown.add(raw.trim());
        }

        if (!unknown.isEmpty()) {
            throw ApiException.badRequest(
                "Not a language this system knows: " + String.join(", ", unknown)
                + ". Pick from " + Languages.CATALOG.stream().map(Languages.Known::id)
                    .collect(java.util.stream.Collectors.joining(", ")) + ".");
        }

        return ids.isEmpty() ? null : String.join(",", ids);
    }

    private void requireWindowOrder(Instant startsAt, Instant endsAt) {
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw ApiException.badRequest("It has to end after it starts.");
        }
    }

    private GroupContest.Lifecycle parseLifecycle(String raw) {
        try {
            return GroupContest.Lifecycle.valueOf(
                raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(
                "Lifecycle must be DRAFT, SCHEDULED, ACTIVE, ENDED or ARCHIVED");
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
