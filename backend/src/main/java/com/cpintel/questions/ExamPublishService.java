package com.cpintel.questions;

import com.cpintel.compete.DomjudgeContestCache;
import com.cpintel.entity.BankQuestion;
import com.cpintel.entity.ContestProblem;
import com.cpintel.entity.ExamAnnouncement;
import com.cpintel.entity.ExamBankQuestion;
import com.cpintel.entity.ExamPublication;
import com.cpintel.entity.GroupContest;
import com.cpintel.events.EventService;
import com.cpintel.events.EventsDto;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.domjudge.DjModels;
import com.cpintel.integration.domjudge.DomjudgeClient;
import com.cpintel.integration.domjudge.DomjudgeJudges;
import com.cpintel.integration.domjudge.JudgeContestRef;
import com.cpintel.repository.jpa.BankQuestionRepository;
import com.cpintel.repository.jpa.ContestProblemRepository;
import com.cpintel.repository.jpa.ExamAnnouncementRepository;
import com.cpintel.repository.jpa.ExamBankQuestionRepository;
import com.cpintel.repository.jpa.ExamPublicationRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Puts an examination's bank questions on the classroom's judge, admin only.
 *
 * <p><b>Before the start</b>, {@link #publish} makes the judge match the admin's choice:
 * <ol>
 *   <li>The contest the examination names is created, once, by CPIntel, not public. A contest
 *       of that id that CPIntel did not create is never published into: it would mix the
 *       bank's questions with somebody else's.</li>
 *   <li>Each chosen question is uploaded as a package (the bank's own export), as problem
 *       {@code cpi-e<exam>-q<question>}, and labelled A, B, C… in the chosen order. A question
 *       already on the judge at its current version and label is left alone.</li>
 *   <li>Problems of this examination no longer chosen are taken out of the contest.</li>
 *   <li>The examination's problem list is set from the result, keeping marks already given.</li>
 * </ol>
 * Every step is safe to repeat, so a publish that failed half way is finished by publishing
 * again.
 *
 * <p><b>After the start</b> the contest's problems are fixed, and {@link #republish} replaces a
 * single question's package in place (same problem, same label), for a mistake found during the
 * paper. It needs an explicit confirmation, can post a notice to every candidate, and leaves
 * re-judging to the existing per-problem rejudge, which the page offers next.
 *
 * <p><b>Limits of the judge's API</b>, said where they bite: an imported contest is open to every
 * team on the judge; its duration cannot be changed afterwards, so it is created to run
 * {@link #END_MARGIN} past the examination's end, to leave room to extend the paper; and its
 * start can be moved only until shortly before it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExamPublishService {

    /** How long the judge's contest runs past the examination's end, for extensions. */
    static final Duration END_MARGIN = Duration.ofHours(3);
    private static final Pattern JUDGE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,60}");
    private static final String LABELS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final EventService events;
    private final QuestionBankService bank;
    private final BankQuestionRepository questions;
    private final ExamBankQuestionRepository chosen;
    private final ExamPublicationRepository publications;
    private final ExamAnnouncementRepository announcements;
    private final ContestProblemRepository contestProblems;
    private final UserRepository users;
    private final DomjudgeJudges judges;
    private final DomjudgeContestCache contestCache;
    private final AuditService auditService;

    /** One publish per examination at a time; a second click is told to wait. */
    private final Map<Long, ReentrantLock> busy = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public ExamBankDto.State state(Long eventId) {
        GroupContest event = events.require(eventId);
        Instant now = Instant.now();
        List<ExamBankQuestion> rows = chosen.findByContestIdOrderByOrdinalAsc(eventId);
        Map<Long, BankQuestion> byId = new HashMap<>();
        questions.findAllById(rows.stream().map(ExamBankQuestion::getQuestionId).toList())
            .forEach(q -> byId.put(q.getQuestionId(), q));

        List<ExamBankDto.Chosen> list = new ArrayList<>();
        for (ExamBankQuestion r : rows) {
            BankQuestion q = byId.get(r.getQuestionId());
            if (q == null) continue;
            list.add(new ExamBankDto.Chosen(q.getQuestionId(), q.getTitle(), q.getStatus(), q.getVersion(),
                Boolean.TRUE.equals(q.getArchived()), r.getLabel(), r.getJudgeProblemId(), r.getPublishedVersion(),
                r.getPublishedAt(), r.getPublishedVersion() != null && !r.getPublishedVersion().equals(q.getVersion())));
        }
        List<ExamBankDto.Available> available = questions
            .findByClassroomIdAndArchivedOrderByUpdatedAtDesc(event.getClassroomId(), false).stream()
            .map(q -> new ExamBankDto.Available(q.getQuestionId(), q.getTitle(), q.getStatus(), q.getVersion()))
            .toList();
        ExamBankDto.Publication publication = publications.findById(eventId).map(p ->
            new ExamBankDto.Publication(p.getStatus(), Boolean.TRUE.equals(p.getContestCreated()), p.getJudgeStart(),
                p.getJudgeEnd(), p.getPublishedAt(), nameOf(p.getPublishedBy()), p.getNote())).orElse(null);
        return new ExamBankDto.State(!event.hasStarted(now) && !event.isCompleted(),
            event.getStartsAt() != null && event.getEndsAt() != null,
            JudgeContestRef.judgeIdOf(event.getExternalId()), publication, list, available, announcementsOf(eventId));
    }

    // ------------------------------------------------------------------ choosing

    /** The examination's questions, in order. Only before the start; publishing applies them. */
    @Transactional
    public ExamBankDto.State choose(Long adminId, Long eventId, List<Long> questionIds, HttpServletRequest httpReq) {
        GroupContest event = requireDomjudge(eventId);
        if (event.hasStarted(Instant.now()) || event.isCompleted()) {
            throw ApiException.conflict("It has started, so its questions are fixed. A single question "
                + "can still be corrected: publish it again.");
        }
        if (questionIds.size() > LABELS.length()) {
            throw ApiException.badRequest("An examination can have at most " + LABELS.length() + " questions.");
        }
        if (new HashSet<>(questionIds).size() != questionIds.size()) {
            throw ApiException.badRequest("A question is chosen twice.");
        }
        Map<Long, BankQuestion> byId = new HashMap<>();
        questions.findAllById(questionIds).forEach(q -> byId.put(q.getQuestionId(), q));
        for (Long id : questionIds) {
            BankQuestion q = byId.get(id);
            if (q == null || !q.getClassroomId().equals(event.getClassroomId())) {
                throw ApiException.badRequest("Question " + id + " is not in this classroom's bank.");
            }
            if (Boolean.TRUE.equals(q.getArchived())) {
                throw ApiException.badRequest("\"" + q.getTitle() + "\" is archived. Restore it to use it.");
            }
        }
        Map<Long, ExamBankQuestion> existing = new HashMap<>();
        chosen.findByContestIdOrderByOrdinalAsc(eventId).forEach(r -> existing.put(r.getQuestionId(), r));
        for (ExamBankQuestion r : existing.values()) {
            if (!questionIds.contains(r.getQuestionId())) chosen.delete(r);
        }
        chosen.flush();
        int ordinal = 0;
        for (Long id : questionIds) {
            ExamBankQuestion r = existing.getOrDefault(id,
                ExamBankQuestion.builder().contestId(eventId).questionId(id).build());
            r.setOrdinal(++ordinal);
            chosen.save(r);
        }
        auditService.recordIn(event.getClassroomId(), adminId, AuditService.EXAM_QUESTIONS_CHOSEN, event.getKind(),
            eventId + ":" + questionIds.size(), httpReq);
        return state(eventId);
    }

    // ------------------------------------------------------------------ publishing

    public ExamBankDto.State publish(Long adminId, Long eventId, HttpServletRequest httpReq) {
        ReentrantLock lock = busy.computeIfAbsent(eventId, k -> new ReentrantLock());
        if (!lock.tryLock()) throw ApiException.conflict("This examination is being published already.");
        try {
            return publishLocked(adminId, eventId, httpReq);
        } finally {
            lock.unlock();
        }
    }

    private ExamBankDto.State publishLocked(Long adminId, Long eventId, HttpServletRequest httpReq) {
        GroupContest event = requireDomjudge(eventId);
        Instant now = Instant.now();
        if (event.isCompleted()) throw ApiException.conflict("It is marked done.");
        if (event.hasStarted(now)) {
            throw ApiException.conflict("It has started, so the contest is fixed. A single question can "
                + "still be corrected: publish it again.");
        }
        if (event.getStartsAt() == null || event.getEndsAt() == null) {
            throw ApiException.badRequest("Set the examination's start and end before publishing.");
        }
        List<ExamBankQuestion> rows = chosen.findByContestIdOrderByOrdinalAsc(eventId);
        if (rows.isEmpty()) throw ApiException.badRequest("Choose the questions first.");
        Map<Long, BankQuestion> byId = new HashMap<>();
        questions.findAllById(rows.stream().map(ExamBankQuestion::getQuestionId).toList())
            .forEach(q -> byId.put(q.getQuestionId(), q));
        List<String> notReady = rows.stream().map(r -> byId.get(r.getQuestionId()))
            .filter(q -> q == null || q.getStatus() != BankQuestion.Status.READY || Boolean.TRUE.equals(q.getArchived()))
            .map(q -> q == null ? "a missing question" : "\"" + q.getTitle() + "\"").toList();
        if (!notReady.isEmpty()) {
            throw ApiException.badRequest("Every question must pass its check first. Not ready: "
                + String.join(", ", notReady) + ".");
        }

        String cid = JudgeContestRef.parse(event.getExternalId()).contestId();
        if (!JUDGE_ID.matcher(cid).matches()) {
            throw ApiException.badRequest("The judge contest id \"" + cid + "\" can only use letters, digits, "
                + "'_', '.' and '-' (at most 60). Change it in the examination's settings.");
        }
        DomjudgeClient judge = adminJudge(event);

        ExamPublication pub = publications.findById(eventId).orElseGet(() ->
            ExamPublication.builder().contestId(eventId).judgeContestId(cid).contestCreated(false)
                .status(ExamPublication.Status.FAILED).build());
        List<String> notes = new ArrayList<>();
        try {
            boolean fresh = false;
            if (!Boolean.TRUE.equals(pub.getContestCreated()) || !cid.equals(pub.getJudgeContestId())) {
                if (judge.findContest(cid) != null) {
                    throw ApiException.conflict("The judge already has a contest \"" + cid + "\" that CPIntel "
                        + "did not create. Questions from the bank go only into a contest CPIntel creates: "
                        + "give the examination a new judge contest id in its settings.");
                }
                Duration length = Duration.between(event.getStartsAt(), event.getEndsAt()).plus(END_MARGIN);
                judge.createContest(cid, event.getName(), event.getStartsAt(), length);
                pub.setJudgeContestId(cid);
                pub.setContestCreated(true);
                pub.setJudgeStart(event.getStartsAt());
                pub.setJudgeEnd(event.getStartsAt().plus(length));
                publications.save(pub);
                fresh = true;
            } else {
                if (judge.findContest(cid) == null) {
                    throw ApiException.conflict("The contest \"" + cid + "\" is no longer on the judge. Give the "
                        + "examination a new judge contest id in its settings and publish again.");
                }
                if (!event.getStartsAt().equals(pub.getJudgeStart())) {
                    Duration length = Duration.between(pub.getJudgeStart(), pub.getJudgeEnd());
                    judge.setContestStart(cid, event.getStartsAt());
                    pub.setJudgeStart(event.getStartsAt());
                    pub.setJudgeEnd(event.getStartsAt().plus(length));
                    publications.save(pub);
                }
            }
            if (event.getEndsAt().isAfter(pub.getJudgeEnd())) {
                notes.add("The judge's contest closes at " + pub.getJudgeEnd() + ", before the examination's end; "
                    + "DOMjudge's API cannot lengthen a contest, so extend it in DOMjudge's jury pages.");
            }

            Set<String> keep = new HashSet<>();
            for (int i = 0; i < rows.size(); i++) {
                ExamBankQuestion r = rows.get(i);
                BankQuestion q = byId.get(r.getQuestionId());
                String label = String.valueOf(LABELS.charAt(i));
                String problemId = problemId(eventId, q.getQuestionId());
                keep.add(problemId);
                boolean current = !fresh && problemId.equals(r.getJudgeProblemId()) && label.equals(r.getLabel())
                    && Objects.equals(r.getPublishedVersion(), q.getVersion());
                if (current) continue;
                judge.uploadProblem(cid, problemId, bank.buildPackage(q.getQuestionId()), r.getJudgeProblemId() != null);
                judge.labelProblem(cid, problemId, label);
                r.setJudgeProblemId(problemId);
                r.setLabel(label);
                r.setPublishedVersion(q.getVersion());
                r.setPublishedAt(Instant.now());
                r.setPublishedBy(adminId);
                chosen.save(r);
            }
            String ours = "cpi-e" + eventId + "-";
            for (DjModels.ContestProblem p : judge.getProblems(cid)) {
                if (p.getId() != null && p.getId().startsWith(ours) && !keep.contains(p.getId())) {
                    judge.unlinkProblem(cid, p.getId());
                }
            }
            // Keyed by the qualified id; the sample cache by the judge's own.
            contestCache.evict(event.getExternalId());

            Map<String, Double> marks = new HashMap<>();
            for (EventsDto.ProblemRow row : events.problems(eventId)) {
                if (row.externalId() != null && row.points() != null) marks.put(row.externalId(), row.points());
            }
            List<EventsDto.ProblemRequest> list = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                ExamBankQuestion r = rows.get(i);
                list.add(new EventsDto.ProblemRequest(r.getLabel(), byId.get(r.getQuestionId()).getTitle(),
                    r.getJudgeProblemId(), i, marks.get(r.getJudgeProblemId())));
            }
            events.setProblems(adminId, eventId, new EventsDto.ProblemsRequest(list), httpReq);

            notes.add(0, "The contest is not public, but DOMjudge opens an imported contest to every team on "
                + "the judge; it should hold only this course's teams.");
            pub.setStatus(ExamPublication.Status.PUBLISHED);
            pub.setPublishedAt(Instant.now());
            pub.setPublishedBy(adminId);
            pub.setNote(String.join(" ", notes));
            publications.save(pub);
        } catch (ApiException e) {
            pub.setStatus(ExamPublication.Status.FAILED);
            pub.setPublishedAt(Instant.now());
            pub.setPublishedBy(adminId);
            pub.setNote(shorten("Publishing stopped: " + e.getMessage() + " Publishing again continues from here."));
            publications.save(pub);
            throw e;
        }
        auditService.recordIn(event.getClassroomId(), adminId, AuditService.EXAM_PUBLISHED, event.getKind(),
            eventId + ":" + cid + ":" + rows.size(), httpReq);
        return state(eventId);
    }

    /**
     * Replaces one question's package on the judge during the examination: same problem, same
     * label. The question must have passed its check at its new version.
     */
    public ExamBankDto.State republish(Long adminId, Long eventId, Long questionId,
                                       ExamBankDto.RepublishRequest req, HttpServletRequest httpReq) {
        if (req == null || !req.confirm()) {
            throw ApiException.badRequest("Confirm that the question should change on the judge.");
        }
        ReentrantLock lock = busy.computeIfAbsent(eventId, k -> new ReentrantLock());
        if (!lock.tryLock()) throw ApiException.conflict("This examination is being published already.");
        try {
            GroupContest event = requireDomjudge(eventId);
            if (event.isCompleted()) throw ApiException.conflict("It is marked done. Reopen it first.");
            ExamPublication pub = publications.findById(eventId)
                .filter(p -> Boolean.TRUE.equals(p.getContestCreated()))
                .orElseThrow(() -> ApiException.conflict("Publish the examination first."));
            ExamBankQuestion r = chosen.findById(new ExamBankQuestion.Key(eventId, questionId))
                .filter(x -> x.getJudgeProblemId() != null)
                .orElseThrow(() -> ApiException.notFound("That question is not published in this examination."));
            BankQuestion q = questions.findById(questionId)
                .orElseThrow(() -> ApiException.notFound("No question with id " + questionId + "."));
            if (q.getStatus() != BankQuestion.Status.READY) {
                throw ApiException.badRequest("\"" + q.getTitle() + "\" has changed since its last check. "
                    + "Check it again; only a passing version can go to the judge.");
            }
            if (q.getVersion().equals(r.getPublishedVersion())) {
                throw ApiException.badRequest("The judge already has this version of \"" + q.getTitle() + "\".");
            }
            DomjudgeClient judge = adminJudge(event);
            String cid = pub.getJudgeContestId();
            judge.uploadProblem(cid, r.getJudgeProblemId(), bank.buildPackage(questionId), true);
            judge.samples().evict(cid, r.getJudgeProblemId());
            // Keyed by the qualified id; the sample cache by the judge's own.
            contestCache.evict(event.getExternalId());

            r.setPublishedVersion(q.getVersion());
            r.setPublishedAt(Instant.now());
            r.setPublishedBy(adminId);
            chosen.save(r);
            contestProblems.findByContestContestIdAndLabelIgnoreCase(eventId, r.getLabel())
                .ifPresent(p -> retitle(p, q.getTitle()));
            if (StringUtils.hasText(req.notice())) {
                announcements.save(ExamAnnouncement.builder().contestId(eventId)
                    .message(req.notice().strip()).createdBy(adminId).build());
            }
            auditService.recordIn(event.getClassroomId(), adminId, AuditService.EXAM_QUESTION_REPUBLISHED,
                event.getKind(), eventId + ":" + r.getLabel() + ":v" + q.getVersion(), httpReq);
            return state(eventId);
        } finally {
            lock.unlock();
        }
    }

    private void retitle(ContestProblem p, String title) {
        if (!Objects.equals(p.getTitle(), title)) {
            p.setTitle(title);
            contestProblems.save(p);
        }
    }

    // ------------------------------------------------------------------ announcements

    @Transactional
    public List<EventsDto.Announcement> announce(Long adminId, Long eventId, String message, HttpServletRequest httpReq) {
        GroupContest event = events.require(eventId);
        if (event.isCompleted()) throw ApiException.conflict("It is marked done.");
        announcements.save(ExamAnnouncement.builder().contestId(eventId).message(message.strip()).createdBy(adminId).build());
        auditService.recordIn(event.getClassroomId(), adminId, AuditService.EXAM_ANNOUNCED, event.getKind(),
            String.valueOf(eventId), httpReq);
        return announcementsOf(eventId);
    }

    private List<EventsDto.Announcement> announcementsOf(Long eventId) {
        return announcements.findByContestIdOrderByCreatedAtAsc(eventId).stream()
            .map(a -> new EventsDto.Announcement(a.getAnnouncementId(), a.getMessage(), a.getCreatedAt())).toList();
    }

    // ------------------------------------------------------------------ helpers

    static String problemId(Long eventId, Long questionId) {
        return "cpi-e" + eventId + "-q" + questionId;
    }

    private GroupContest requireDomjudge(Long eventId) {
        GroupContest event = events.require(eventId);
        if (!"DOMJUDGE".equals(event.getPlatform())) {
            throw ApiException.badRequest("Only an examination on DOMjudge can take questions from the bank.");
        }
        return event;
    }

    /** The classroom's judge, as an account allowed to create contests and problems. */
    private DomjudgeClient adminJudge(GroupContest event) {
        DomjudgeClient judge = judges.forClassroom(event.getClassroomId());
        if (!judge.hasServiceAccount()) {
            throw ApiException.badRequest("Publishing needs the classroom's DOMjudge service account. "
                + "A superadmin can set it on the classroom's page.");
        }
        if (!judge.serviceAccountIsAdmin()) {
            throw ApiException.badRequest("The classroom's DOMjudge service account is not an admin on the "
                + "judge, and DOMjudge lets only admins create contests and problems. Ask whoever runs "
                + "the judge to give it the admin role.");
        }
        return judge;
    }

    private String nameOf(Long userId) {
        if (userId == null) return null;
        return users.findById(userId)
            .map(u -> StringUtils.hasText(u.getFullName()) ? u.getFullName() : u.getUsername()).orElse(null);
    }

    private static String shorten(String s) {
        return s.length() > 2000 ? s.substring(0, 1997) + "…" : s;
    }
}
