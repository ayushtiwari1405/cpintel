package com.cpintel.questions;

import com.cpintel.entity.BankQuestion;
import com.cpintel.entity.BankQuestionCheck;
import com.cpintel.entity.BankQuestionTest;
import com.cpintel.entity.BankQuestionTestData;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.BankQuestionCheckRepository;
import com.cpintel.repository.jpa.BankQuestionRepository;
import com.cpintel.repository.jpa.BankQuestionTestDataRepository;
import com.cpintel.repository.jpa.BankQuestionTestRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.runner.CodeRunnerService;
import com.cpintel.runner.RunDto;
import com.cpintel.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A model solution checked against a question's tests, with the runner's answers scripted. */
class QuestionCheckServiceTest {

    private static final long USER = 2L;
    private static final long Q = 40L;

    private final QuestionBankService bank = mock(QuestionBankService.class);
    private final BankQuestionRepository questions = mock(BankQuestionRepository.class);
    private final BankQuestionTestRepository tests = mock(BankQuestionTestRepository.class);
    private final BankQuestionTestDataRepository testData = mock(BankQuestionTestDataRepository.class);
    private final BankQuestionCheckRepository checks = mock(BankQuestionCheckRepository.class);
    private final CodeRunnerService runner = mock(CodeRunnerService.class);
    private QuestionCheckService service;

    private BankQuestion question;
    private final List<BankQuestionTest> stored = new ArrayList<>();
    private final Map<Long, BankQuestionTestData> data = new HashMap<>();
    private final Map<Long, BankQuestionCheck> rows = new HashMap<>();
    /** What the runner prints for an input; null means it crashes. */
    private Function<String, String> program = in -> in;
    private long durationMs = 100;
    private final List<RunDto.RunLimits> limitsSeen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new QuestionCheckService(bank, questions, tests, testData, checks,
            mock(UserRepository.class), runner, mock(AuditService.class), new ObjectMapper()
                .findAndRegisterModules(), tm);
        service.dispatch = Runnable::run;

        question = BankQuestion.builder().questionId(Q).classroomId(5L).title("Echo").version(3)
            .timeLimitMs(1000).memoryLimitMb(256).checker(BankQuestion.Checker.EXACT)
            .modelLanguage("cpp").modelSource("int main(){}").build();
        when(bank.requireEditable(USER, Q)).thenReturn(question);
        when(questions.findById(Q)).thenReturn(Optional.of(question));
        when(tests.findOrdered(Q)).thenAnswer(inv -> List.copyOf(stored));
        when(testData.findAllById(any())).thenAnswer(inv -> List.copyOf(data.values()));
        when(checks.findById(Q)).thenAnswer(inv -> Optional.ofNullable(rows.get(Q)));
        when(checks.save(any())).thenAnswer(inv -> {
            BankQuestionCheck c = inv.getArgument(0);
            rows.put(c.getQuestionId(), c);
            return c;
        });
        when(bank.markReady(eq(Q), anyInt())).thenAnswer(inv -> inv.getArgument(1, Integer.class) == question.getVersion());
        when(runner.runWithLimits(any(), any(), any(), any())).thenAnswer(inv -> {
            List<RunDto.TestCase> cases = inv.getArgument(2);
            limitsSeen.add(inv.getArgument(3));
            List<RunDto.TestResult> results = new ArrayList<>();
            for (RunDto.TestCase c : cases) {
                String out = program.apply(c.input());
                results.add(out == null
                    ? new RunDto.TestResult(c.label(), RunDto.Verdict.RUNTIME_ERROR, c.input(), null, "", "Segmentation fault", durationMs, 139, false, null)
                    : new RunDto.TestResult(c.label(), RunDto.Verdict.NO_EXPECTED, c.input(), null, out, "", durationMs, null, false, null));
            }
            return new RunDto.RunResponse(true, null, 50, results, null);
        });
    }

    private void test(long id, boolean sample, String input, String answer) {
        stored.add(BankQuestionTest.builder().testId(id).questionId(Q).sample(sample)
            .ordinal((int) stored.stream().filter(t -> t.getSample() == sample).count() + 1)
            .name(String.valueOf(id)).inputBytes(input.length())
            .answerBytes(answer == null ? null : answer.length()).build());
        data.put(id, BankQuestionTestData.builder().testId(id).input(input.getBytes(StandardCharsets.UTF_8))
            .answer(answer == null ? null : answer.getBytes(StandardCharsets.UTF_8)).build());
    }

    /** Starts a check, which runs to the end on this thread. */
    private BankQuestionCheck check(boolean generate) {
        service.start(USER, Q, generate, null);
        return rows.get(Q);
    }

    @Test
    @DisplayName("A correct solution passes every test and makes the question ready")
    void passes() {
        test(1, true, "1 2\n", "1 2\n");
        test(2, false, "hello\n", "hello\n");

        BankQuestionCheck c = check(false);

        assertEquals(BankQuestionCheck.Status.PASSED, c.getStatus(), c.getSummary());
        assertTrue(c.getSummary().startsWith("All 2 tests pass"), c.getSummary());
        verify(bank).markReady(Q, 3);
        // The question's own limits, with time to measure a slow solution, and room for its output.
        assertEquals(2000, limitsSeen.get(0).timeLimitMs());
        assertEquals(256, limitsSeen.get(0).memoryLimitMb());
    }

    @Test
    @DisplayName("A wrong answer fails the check, says where, and leaves the question a draft")
    void wrongAnswer() {
        test(1, false, "1 2\n", "1 3\n");

        BankQuestionCheck c = check(false);

        assertEquals(BankQuestionCheck.Status.FAILED, c.getStatus());
        assertTrue(c.getSummary().contains("token 2: expected \"3\", got \"2\""), c.getSummary());
        verify(bank, never()).markReady(anyLong(), anyInt());
    }

    @Test
    @DisplayName("Too slow, close to the limit, or crashing each fail the check")
    void timingAndCrashes() {
        test(1, false, "x\n", "x\n");
        durationMs = 700;
        assertTrue(check(false).getResults().contains("CLOSE_TO_LIMIT"));
        durationMs = 1500;
        assertTrue(check(false).getResults().contains("\"TIME_LIMIT\""));
        durationMs = 100;
        program = in -> null;
        BankQuestionCheck c = check(false);
        assertEquals(BankQuestionCheck.Status.FAILED, c.getStatus());
        assertTrue(c.getResults().contains("RUNTIME_ERROR") && c.getResults().contains("Segmentation fault"), c.getResults());
    }

    @Test
    @DisplayName("The question's own comparison applies: a tolerance accepts a near answer")
    void comparisonRule() {
        question.setChecker(BankQuestion.Checker.FLOAT);
        question.setFloatTolerance(1e-6);
        test(1, false, "0.3333334\n", "0.3333333\n");

        assertEquals(BankQuestionCheck.Status.PASSED, check(false).getStatus());
    }

    @Test
    @DisplayName("Missing answers fail the check unless generation is asked for")
    void missingAnswers() {
        test(1, false, "a\n", null);

        BankQuestionCheck c = check(false);
        assertEquals(BankQuestionCheck.Status.FAILED, c.getStatus());
        assertTrue(c.getSummary().contains("no answer"), c.getSummary());
        verify(bank, never()).storeGeneratedAnswers(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("Generating stores the model solution's outputs as a new version, then checks that version")
    void generates() {
        test(1, true, "1\n", "1\n");
        test(2, false, "two\n", null);
        when(bank.storeGeneratedAnswers(eq(Q), eq(3), any(), eq(USER))).thenAnswer(inv -> {
            question.setVersion(4);
            return 4;
        });

        BankQuestionCheck c = check(true);

        assertEquals(BankQuestionCheck.Status.PASSED, c.getStatus(), c.getSummary());
        assertEquals(4, c.getVersion());
        verify(bank).storeGeneratedAnswers(eq(Q), eq(3), argThat(m -> m.size() == 1
            && new String(m.get(2L), StandardCharsets.UTF_8).equals("two\n")), eq(USER));
        verify(bank).markReady(Q, 4);
    }

    @Test
    @DisplayName("If the solution cannot produce an answer, nothing is generated")
    void generationFails() {
        test(1, false, "boom\n", null);
        program = in -> null;

        BankQuestionCheck c = check(true);

        assertEquals(BankQuestionCheck.Status.FAILED, c.getStatus());
        assertTrue(c.getSummary().contains("Nothing was saved"), c.getSummary());
        verify(bank, never()).storeGeneratedAnswers(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("An edit during the check leaves the question a draft, and says so")
    void editedMeanwhile() {
        test(1, false, "x\n", "x\n");
        when(bank.markReady(Q, 3)).thenReturn(false);

        BankQuestionCheck c = check(false);

        assertEquals(BankQuestionCheck.Status.PASSED, c.getStatus());
        assertTrue(c.getSummary().contains("still a draft"), c.getSummary());
    }

    @Test
    @DisplayName("A compile error fails with the compiler's words; an unavailable runner is an error")
    void compileAndRunner() {
        test(1, false, "x\n", "x\n");
        doReturn(RunDto.RunResponse.failedToCompile("main.cpp:1: error: expected ';'", 10))
            .when(runner).runWithLimits(any(), any(), any(), any());
        BankQuestionCheck c = check(false);
        assertEquals(BankQuestionCheck.Status.FAILED, c.getStatus());
        assertTrue(c.getSummary().contains("does not compile") && c.getSummary().contains("expected ';'"));

        doReturn(RunDto.RunResponse.unavailable("The code runner is not reachable right now."))
            .when(runner).runWithLimits(any(), any(), any(), any());
        assertEquals(BankQuestionCheck.Status.ERROR, check(false).getStatus());
    }

    @Test
    @DisplayName("Tests go to the runner in batches")
    void batches() {
        for (int i = 1; i <= QuestionCheckService.BATCH_TESTS + 5; i++) test(i, false, i + "\n", i + "\n");

        assertEquals(BankQuestionCheck.Status.PASSED, check(false).getStatus());
        verify(runner, times(2)).runWithLimits(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A check needs a model solution and tests, and only one runs per question")
    void refusals() {
        question.setModelSource(null);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
            () -> service.start(USER, Q, false, null)).getStatus());

        question.setModelSource("int main(){}");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
            () -> service.start(USER, Q, false, null)).getStatus());

        test(1, false, "x\n", "x\n");
        rows.put(Q, BankQuestionCheck.builder().questionId(Q).version(3)
            .status(BankQuestionCheck.Status.RUNNING).startedAt(java.time.Instant.now()).build());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
            () -> service.start(USER, Q, false, null)).getStatus());
    }
}
