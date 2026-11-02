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
import com.cpintel.integration.domjudge.DomjudgeSampleClient;
import com.cpintel.repository.jpa.BankQuestionRepository;
import com.cpintel.repository.jpa.ContestProblemRepository;
import com.cpintel.repository.jpa.ExamAnnouncementRepository;
import com.cpintel.repository.jpa.ExamBankQuestionRepository;
import com.cpintel.repository.jpa.ExamPublicationRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Publishing an examination's bank questions to a judge, which is mocked. */
class ExamPublishServiceTest {

    private static final long ADMIN = 1L;
    private static final long EXAM = 7L;
    private static final long ROOM = 5L;

    private final EventService events = mock(EventService.class);
    private final QuestionBankService bank = mock(QuestionBankService.class);
    private final BankQuestionRepository questions = mock(BankQuestionRepository.class);
    private final ExamBankQuestionRepository chosen = mock(ExamBankQuestionRepository.class);
    private final ExamPublicationRepository publications = mock(ExamPublicationRepository.class);
    private final ExamAnnouncementRepository announcements = mock(ExamAnnouncementRepository.class);
    private final ContestProblemRepository contestProblems = mock(ContestProblemRepository.class);
    private final DomjudgeJudges judges = mock(DomjudgeJudges.class);
    private final DomjudgeClient judge = mock(DomjudgeClient.class);
    private final DomjudgeSampleClient samples = mock(DomjudgeSampleClient.class);
    private final DomjudgeContestCache cache = mock(DomjudgeContestCache.class);
    private ExamPublishService service;

    private GroupContest exam;
    private final Map<Long, BankQuestion> bankQuestions = new HashMap<>();
    private final List<ExamBankQuestion> rows = new ArrayList<>();
    private final Map<Long, ExamPublication> pubs = new HashMap<>();
    private final List<DjModels.ContestProblem> onJudge = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ExamPublishService(events, bank, questions, chosen, publications, announcements,
            contestProblems, mock(UserRepository.class), judges, cache, mock(AuditService.class));
        Instant start = Instant.now().plus(Duration.ofDays(1));
        exam = GroupContest.builder().contestId(EXAM).classroomId(ROOM).kind("EXAM").platform("DOMJUDGE")
            .externalId(ROOM + "~midterm").name("Midterm").startsAt(start).endsAt(start.plus(Duration.ofHours(2))).build();
        when(events.require(EXAM)).thenReturn(exam);
        when(events.problems(EXAM)).thenReturn(List.of());
        when(questions.findAllById(any())).thenAnswer(inv -> {
            List<BankQuestion> out = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) if (bankQuestions.containsKey(id)) out.add(bankQuestions.get(id));
            return out;
        });
        when(questions.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(bankQuestions.get(inv.getArgument(0, Long.class))));
        when(chosen.findByContestIdOrderByOrdinalAsc(EXAM)).thenAnswer(inv -> List.copyOf(rows));
        when(chosen.findById(any())).thenAnswer(inv -> {
            ExamBankQuestion.Key k = inv.getArgument(0);
            return rows.stream().filter(r -> r.getQuestionId().equals(questionOf(k))).findFirst();
        });
        when(publications.findById(EXAM)).thenAnswer(inv -> Optional.ofNullable(pubs.get(EXAM)));
        when(publications.save(any())).thenAnswer(inv -> {
            ExamPublication p = inv.getArgument(0);
            pubs.put(p.getContestId(), p);
            return p;
        });
        when(judges.forClassroom(ROOM)).thenReturn(judge);
        when(judge.hasServiceAccount()).thenReturn(true);
        when(judge.serviceAccountIsAdmin()).thenReturn(true);
        when(judge.getProblems("midterm")).thenAnswer(inv -> List.copyOf(onJudge));
        when(judge.samples()).thenReturn(samples);
        when(bank.buildPackage(anyLong())).thenReturn(new byte[] {1});
    }

    private static Long questionOf(ExamBankQuestion.Key k) {
        try {
            var f = ExamBankQuestion.Key.class.getDeclaredField("questionId");
            f.setAccessible(true);
            return (Long) f.get(k);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private BankQuestion question(long id, String title, BankQuestion.Status status) {
        BankQuestion q = BankQuestion.builder().questionId(id).classroomId(ROOM).title(title).status(status).version(2).build();
        bankQuestions.put(id, q);
        rows.add(ExamBankQuestion.builder().contestId(EXAM).questionId(id).ordinal(rows.size() + 1).build());
        return q;
    }

    private static HttpStatus status(Runnable r) {
        return assertThrows(ApiException.class, r::run).getStatus();
    }

    @Test
    @DisplayName("The first publish creates the contest with room past the end, uploads and labels A, B, and sets the problem list")
    void firstPublish() {
        question(10, "Sum", BankQuestion.Status.READY);
        question(11, "Paths", BankQuestion.Status.READY);

        service.publish(ADMIN, EXAM, null);

        verify(judge).createContest(eq("midterm"), eq("Midterm"), eq(exam.getStartsAt()),
            eq(Duration.ofHours(2).plus(ExamPublishService.END_MARGIN)));
        verify(judge).uploadProblem("midterm", "cpi-e7-q10", new byte[] {1}, false);
        verify(judge).labelProblem("midterm", "cpi-e7-q10", "A");
        verify(judge).labelProblem("midterm", "cpi-e7-q11", "B");
        ArgumentCaptor<EventsDto.ProblemsRequest> list = ArgumentCaptor.forClass(EventsDto.ProblemsRequest.class);
        verify(events).setProblems(eq(ADMIN), eq(EXAM), list.capture(), any());
        assertEquals(List.of("A", "B"), list.getValue().problems().stream().map(EventsDto.ProblemRequest::label).toList());
        assertEquals("cpi-e7-q11", list.getValue().problems().get(1).externalId());
        assertEquals(ExamPublication.Status.PUBLISHED, pubs.get(EXAM).getStatus());
        assertTrue(pubs.get(EXAM).getNote().contains("every team"));
        verify(cache).evict(ROOM + "~midterm");
    }

    @Test
    @DisplayName("Publishing again leaves current questions alone, uploads changed ones as updates, and drops unchosen ones")
    void republishBeforeStart() {
        question(10, "Sum", BankQuestion.Status.READY);
        question(11, "Paths", BankQuestion.Status.READY);
        service.publish(ADMIN, EXAM, null);
        clearInvocations(judge);

        bankQuestions.get(11L).setVersion(3);
        DjModels.ContestProblem gone = new DjModels.ContestProblem();
        gone.setId("cpi-e7-q99");
        DjModels.ContestProblem foreign = new DjModels.ContestProblem();
        foreign.setId("someone-else");
        onJudge.addAll(List.of(gone, foreign));
        when(judge.findContest("midterm")).thenReturn(new DjModels.Contest());
        when(events.problems(EXAM)).thenReturn(List.of(new EventsDto.ProblemRow(1L, "A", "Sum", "cpi-e7-q10", 0, 40.0)));

        service.publish(ADMIN, EXAM, null);

        verify(judge, never()).createContest(any(), any(), any(), any());
        verify(judge, never()).uploadProblem(eq("midterm"), eq("cpi-e7-q10"), any(), anyBoolean());
        verify(judge).uploadProblem("midterm", "cpi-e7-q11", new byte[] {1}, true);
        verify(judge).unlinkProblem("midterm", "cpi-e7-q99");
        verify(judge, never()).unlinkProblem("midterm", "someone-else");
        ArgumentCaptor<EventsDto.ProblemsRequest> list = ArgumentCaptor.forClass(EventsDto.ProblemsRequest.class);
        verify(events, times(2)).setProblems(eq(ADMIN), eq(EXAM), list.capture(), any());
        assertEquals(40.0, list.getValue().problems().get(0).points(), "marks already given are kept");
    }

    @Test
    @DisplayName("A moved start is moved on the judge; a contest CPIntel did not create is never used")
    void startAndForeignContest() {
        question(10, "Sum", BankQuestion.Status.READY);
        when(judge.findContest("midterm")).thenReturn(new DjModels.Contest());
        assertEquals(HttpStatus.CONFLICT, status(() -> service.publish(ADMIN, EXAM, null)));
        verify(judge, never()).createContest(any(), any(), any(), any());
        assertEquals(ExamPublication.Status.FAILED, pubs.get(EXAM).getStatus());

        pubs.get(EXAM).setContestCreated(true);
        pubs.get(EXAM).setJudgeStart(exam.getStartsAt());
        pubs.get(EXAM).setJudgeEnd(exam.getEndsAt().plus(ExamPublishService.END_MARGIN));
        exam.setStartsAt(exam.getStartsAt().plus(Duration.ofHours(1)));
        exam.setEndsAt(exam.getEndsAt().plus(Duration.ofHours(1)));
        service.publish(ADMIN, EXAM, null);
        verify(judge).setContestStart("midterm", exam.getStartsAt());
    }

    @Test
    @DisplayName("Publishing is refused: questions not checked, no admin account, started, no window, a bad id")
    void refusals() {
        BankQuestion q = question(10, "Sum", BankQuestion.Status.DRAFT);
        String draft = assertThrows(ApiException.class, () -> service.publish(ADMIN, EXAM, null)).getMessage();
        assertTrue(draft.contains("\"Sum\""), draft);

        q.setStatus(BankQuestion.Status.READY);
        when(judge.serviceAccountIsAdmin()).thenReturn(false);
        assertTrue(assertThrows(ApiException.class, () -> service.publish(ADMIN, EXAM, null)).getMessage().contains("admin role"));
        when(judge.serviceAccountIsAdmin()).thenReturn(true);

        exam.setExternalId(ROOM + "~mid term");
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.publish(ADMIN, EXAM, null)));
        exam.setExternalId(ROOM + "~midterm");

        exam.setEndsAt(null);
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.publish(ADMIN, EXAM, null)));

        exam.setLifecycle(GroupContest.Lifecycle.SCHEDULED.name());
        exam.setStartsAt(Instant.now().minusSeconds(60));
        exam.setEndsAt(Instant.now().plusSeconds(3600));
        assertEquals(HttpStatus.CONFLICT, status(() -> service.publish(ADMIN, EXAM, null)));
        verify(judge, never()).uploadProblem(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("A failure part way is recorded, and publishing again picks up from it")
    void partialFailure() {
        question(10, "Sum", BankQuestion.Status.READY);
        question(11, "Paths", BankQuestion.Status.READY);
        doThrow(ApiException.badRequest("The judge could not upload cpi-e7-q11: disk full"))
            .when(judge).uploadProblem(eq("midterm"), eq("cpi-e7-q11"), any(), anyBoolean());

        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.publish(ADMIN, EXAM, null)));
        assertEquals(ExamPublication.Status.FAILED, pubs.get(EXAM).getStatus());
        assertTrue(pubs.get(EXAM).getNote().contains("disk full"));
        assertEquals("cpi-e7-q10", rows.get(0).getJudgeProblemId());

        doReturn("cpi-e7-q11").when(judge).uploadProblem(eq("midterm"), eq("cpi-e7-q11"), any(), anyBoolean());
        when(judge.findContest("midterm")).thenReturn(new DjModels.Contest());
        clearInvocations(judge);
        service.publish(ADMIN, EXAM, null);
        verify(judge, never()).createContest(any(), any(), any(), any());
        verify(judge, never()).uploadProblem(eq("midterm"), eq("cpi-e7-q10"), any(), anyBoolean());
        verify(judge).uploadProblem(eq("midterm"), eq("cpi-e7-q11"), any(), eq(false));
        assertEquals(ExamPublication.Status.PUBLISHED, pubs.get(EXAM).getStatus());
    }

    @Test
    @DisplayName("During the paper, a checked new version replaces the problem in place, with a notice")
    void liveFix() {
        BankQuestion q = question(10, "Sum", BankQuestion.Status.READY);
        service.publish(ADMIN, EXAM, null);
        exam.setStartsAt(Instant.now().minusSeconds(600));
        exam.setEndsAt(Instant.now().plusSeconds(3600));
        ContestProblem row = ContestProblem.builder().label("A").title("Sum").build();
        when(contestProblems.findByContestContestIdAndLabelIgnoreCase(EXAM, "A")).thenReturn(Optional.of(row));

        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.republish(ADMIN, EXAM, 10L,
            new ExamBankDto.RepublishRequest(false, null), null)));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.republish(ADMIN, EXAM, 10L,
            new ExamBankDto.RepublishRequest(true, null), null)), "same version as the judge");

        q.setVersion(3);
        q.setTitle("Sum (corrected)");
        q.setStatus(BankQuestion.Status.DRAFT);
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.republish(ADMIN, EXAM, 10L,
            new ExamBankDto.RepublishRequest(true, null), null)), "not checked");

        q.setStatus(BankQuestion.Status.READY);
        clearInvocations(judge);
        service.republish(ADMIN, EXAM, 10L, new ExamBankDto.RepublishRequest(true, "Test 3 of A was wrong."), null);

        verify(judge).uploadProblem("midterm", "cpi-e7-q10", new byte[] {1}, true);
        verify(judge, never()).labelProblem(any(), any(), any());
        verify(samples).evict("midterm", "cpi-e7-q10");
        assertEquals(3, rows.get(0).getPublishedVersion());
        assertEquals("Sum (corrected)", row.getTitle());
        verify(announcements).save(argThat((ExamAnnouncement a) -> a.getMessage().equals("Test 3 of A was wrong.")));
    }

    @Test
    @DisplayName("Choosing questions keeps them in this classroom, unarchived, once each, before the start")
    void choosing() {
        bankQuestions.put(10L, BankQuestion.builder().questionId(10L).classroomId(ROOM).title("A").version(1).build());
        bankQuestions.put(11L, BankQuestion.builder().questionId(11L).classroomId(99L).title("B").version(1).build());
        bankQuestions.put(12L, BankQuestion.builder().questionId(12L).classroomId(ROOM).title("C").archived(true).version(1).build());

        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.choose(ADMIN, EXAM, List.of(10L, 10L), null)));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.choose(ADMIN, EXAM, List.of(11L), null)));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.choose(ADMIN, EXAM, List.of(12L), null)));
        service.choose(ADMIN, EXAM, List.of(10L), null);
        verify(chosen).save(argThat(r -> r.getQuestionId() == 10L && r.getOrdinal() == 1));

        exam.setLifecycle(GroupContest.Lifecycle.SCHEDULED.name());
        exam.setStartsAt(Instant.now().minusSeconds(1));
        assertEquals(HttpStatus.CONFLICT, status(() -> service.choose(ADMIN, EXAM, List.of(10L), null)));
    }
}
