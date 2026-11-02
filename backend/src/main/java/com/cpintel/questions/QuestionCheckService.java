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
import com.cpintel.runner.RunEngine;
import com.cpintel.service.AuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checks a bank question's model solution against its tests, in the code runner, the way the
 * judge will run and judge it.
 *
 * <p>Each test runs under the question's own time and memory limits, and the whole output is
 * compared with the test's answer by {@link OutputChecker}, the judge's rule for the question's
 * comparison setting. A test passes when the output matches, the program ran cleanly, and it took
 * at most half the time limit: the judge's machine may be slower than the runner, and a model
 * solution that only just fits makes the limit a coin toss for everyone else.
 *
 * <p>With "generate missing answers", tests without an answer first get the model solution's
 * output as their answer. That is a change to the question (a new version), recorded like any
 * other, and then every test is checked as usual.
 *
 * <p>A passing check marks the question READY only if the question is still the version that was
 * checked; an edit made while it ran leaves the question a draft.
 *
 * <p><b>Load.</b> One check runs at a time across the server, its tests sent to the runner in
 * batches one after another, so a check holds at most one runner slot and never crowds out
 * candidates' runs. At most {@link #MAX_WAITING} checks wait their turn.
 */
@Service
@Slf4j
public class QuestionCheckService {

    static final int MAX_WAITING = 10;
    /** Inputs and answers per request to the runner, well under its 24 MB request limit. */
    static final long BATCH_BYTES = 12L * 1024 * 1024;
    static final int BATCH_TESTS = 50;
    /** When answers are unknown, their size is too: fewer tests per request. */
    static final int GENERATE_BATCH_TESTS = 10;
    /** A model solution may use at most this share of the time limit on any test. */
    static final double TIME_MARGIN = 0.5;

    /** What happened to one test. */
    public enum Verdict {
        OK, WRONG_ANSWER, TIME_LIMIT, CLOSE_TO_LIMIT, RUNTIME_ERROR, MEMORY_LIMIT,
        OUTPUT_TOO_LARGE, NO_ANSWER, NOT_RUN
    }

    private final QuestionBankService bank;
    private final BankQuestionRepository questions;
    private final BankQuestionTestRepository tests;
    private final BankQuestionTestDataRepository testData;
    private final BankQuestionCheckRepository checks;
    private final UserRepository users;
    private final CodeRunnerService runner;
    private final AuditService auditService;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "question-check");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger waiting = new AtomicInteger();
    /** Where a started check runs: the worker. Tests run it on their own thread instead. */
    java.util.concurrent.Executor dispatch = worker;

    public QuestionCheckService(QuestionBankService bank, BankQuestionRepository questions,
                                BankQuestionTestRepository tests, BankQuestionTestDataRepository testData,
                                BankQuestionCheckRepository checks, UserRepository users,
                                CodeRunnerService runner, AuditService auditService, ObjectMapper json,
                                PlatformTransactionManager transactions) {
        this.bank = bank;
        this.questions = questions;
        this.tests = tests;
        this.testData = testData;
        this.checks = checks;
        this.users = users;
        this.runner = runner;
        this.auditService = auditService;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
    }

    /** A check that was waiting or running when the server stopped will never finish. */
    @EventListener(ApplicationReadyEvent.class)
    public void abandonInterrupted() {
        Integer n = tx.execute(s -> checks.abandonActive(Instant.now()));
        if (n != null && n > 0) log.info("Marked {} question check(s) cut short by a restart", n);
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ API

    public QuestionBankDto.Check start(Long userId, Long questionId, boolean generate,
                                       HttpServletRequest httpReq) {
        BankQuestion q = bank.requireEditable(userId, questionId);
        if (!StringUtils.hasText(q.getModelSource()) || !StringUtils.hasText(q.getModelLanguage())) {
            throw ApiException.badRequest("Add a model solution, and save it, before checking.");
        }
        if (tests.findOrdered(questionId).isEmpty()) {
            throw ApiException.badRequest("Add at least one test before checking.");
        }
        BankQuestionCheck row = tx.execute(s -> {
            BankQuestionCheck existing = checks.findById(questionId).orElse(null);
            if (existing != null && existing.isActive()) {
                throw ApiException.conflict("A check of this question is already running.");
            }
            if (waiting.get() >= MAX_WAITING) {
                throw ApiException.serviceUnavailable("Many checks are waiting already. Try again in a few minutes.");
            }
            BankQuestionCheck c = existing != null ? existing : BankQuestionCheck.builder().questionId(questionId).build();
            c.setVersion(q.getVersion());
            c.setStatus(BankQuestionCheck.Status.QUEUED);
            c.setGenerate(generate);
            c.setStartedBy(userId);
            c.setStartedAt(Instant.now());
            c.setFinishedAt(null);
            c.setSummary(null);
            c.setMaxTimeMs(null);
            c.setResults(null);
            return checks.save(c);
        });
        auditService.recordIn(q.getClassroomId(), userId, AuditService.QUESTION_CHECK_STARTED, "QUESTION",
            String.valueOf(questionId), httpReq);
        waiting.incrementAndGet();
        dispatch.execute(() -> {
            try {
                run(questionId);
            } finally {
                waiting.decrementAndGet();
            }
        });
        return view(row);
    }

    /** The latest check, or null when the question was never checked. */
    public QuestionBankDto.Check latest(Long userId, Long questionId) {
        bank.requireQuestion(userId, questionId);
        return checks.findById(questionId).map(this::view).orElse(null);
    }

    // ------------------------------------------------------------------ the check

    /** Why a check stopped before judging every test. */
    private static final class Stop extends RuntimeException {
        final BankQuestionCheck.Status status;
        final List<QuestionBankDto.CheckedTest> results;

        Stop(BankQuestionCheck.Status status, String summary, List<QuestionBankDto.CheckedTest> results) {
            super(summary);
            this.status = status;
            this.results = results;
        }
    }

    void run(Long questionId) {
        BankQuestionCheck row = checks.findById(questionId).orElse(null);
        if (row == null || row.getStatus() != BankQuestionCheck.Status.QUEUED) return;
        row.setStatus(BankQuestionCheck.Status.RUNNING);
        checks.save(row);
        try {
            judge(row);
        } catch (Stop stop) {
            finish(row, stop.status, stop.getMessage(), stop.results, null);
        } catch (RuntimeException e) {
            log.warn("Question check {} failed: {}", questionId, e.toString());
            finish(row, BankQuestionCheck.Status.ERROR,
                "The check could not finish: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                List.of(), null);
        }
    }

    private void judge(BankQuestionCheck row) {
        Long questionId = row.getQuestionId();
        BankQuestion q = questions.findById(questionId)
            .orElseThrow(() -> new Stop(BankQuestionCheck.Status.ERROR, "The question was deleted.", List.of()));
        if (!q.getVersion().equals(row.getVersion())) {
            throw new Stop(BankQuestionCheck.Status.ERROR,
                "The question changed before the check started. Run it again.", List.of());
        }
        List<BankQuestionTest> ordered = tests.findOrdered(questionId);
        Map<Long, BankQuestionTestData> data = testData.findAllById(
                ordered.stream().map(BankQuestionTest::getTestId).toList()).stream()
            .collect(Collectors.toMap(BankQuestionTestData::getTestId, Function.identity()));

        int version = row.getVersion();
        if (Boolean.TRUE.equals(row.getGenerate())) {
            List<BankQuestionTest> missing = ordered.stream().filter(t -> t.getAnswerBytes() == null).toList();
            if (!missing.isEmpty()) {
                Map<Long, RunDto.TestResult> ran = execute(q, missing, data, true);
                List<QuestionBankDto.CheckedTest> results = new ArrayList<>();
                Map<Long, byte[]> answers = new LinkedHashMap<>();
                for (BankQuestionTest t : missing) {
                    QuestionBankDto.CheckedTest r = verdict(q, t, ran.get(t.getTestId()), null);
                    results.add(r);
                    if (r.verdict().equals(Verdict.OK.name()) || r.verdict().equals(Verdict.CLOSE_TO_LIMIT.name())) {
                        answers.put(t.getTestId(), ran.get(t.getTestId()).actual().getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (answers.size() < missing.size()) {
                    throw new Stop(BankQuestionCheck.Status.FAILED, (missing.size() - answers.size())
                        + " of the " + missing.size() + " missing answers could not be generated: the model "
                        + "solution did not finish cleanly on those tests. Nothing was saved.", results);
                }
                try {
                    version = bank.storeGeneratedAnswers(questionId, version, answers, row.getStartedBy());
                } catch (ApiException e) {
                    throw new Stop(BankQuestionCheck.Status.ERROR, e.getMessage() + " Run the check again.", List.of());
                }
                for (Map.Entry<Long, byte[]> e : answers.entrySet()) {
                    data.get(e.getKey()).setAnswer(e.getValue());
                }
                row.setVersion(version);
                checks.save(row);
            }
        }

        List<BankQuestionTest> unanswered = ordered.stream()
            .filter(t -> data.get(t.getTestId()) == null || data.get(t.getTestId()).getAnswer() == null).toList();
        if (!unanswered.isEmpty()) {
            List<QuestionBankDto.CheckedTest> results = unanswered.stream()
                .map(t -> new QuestionBankDto.CheckedTest(t.getTestId(), t.getSample(), t.getOrdinal(),
                    Verdict.NO_ANSWER.name(), null, "No answer to compare with."))
                .toList();
            throw new Stop(BankQuestionCheck.Status.FAILED, unanswered.size() + " test"
                + (unanswered.size() == 1 ? " has" : "s have") + " no answer. Upload "
                + (unanswered.size() == 1 ? "it" : "them") + ", or check again and generate missing answers.", results);
        }

        Map<Long, RunDto.TestResult> ran = execute(q, ordered, data, false);
        List<QuestionBankDto.CheckedTest> results = new ArrayList<>();
        for (BankQuestionTest t : ordered) {
            results.add(verdict(q, t, ran.get(t.getTestId()), data.get(t.getTestId()).getAnswer()));
        }
        long failed = results.stream().filter(r -> !r.verdict().equals(Verdict.OK.name())).count();
        Integer maxTime = results.stream().map(QuestionBankDto.CheckedTest::timeMs)
            .filter(java.util.Objects::nonNull).max(Long::compare).map(Long::intValue).orElse(null);
        String memoryNote = q.getMemoryLimitMb() > RunEngine.MAX_MEMORY_LIMIT_MB
            ? " Memory was checked at " + RunEngine.MAX_MEMORY_LIMIT_MB + " MB, the most the runner allows, not "
              + q.getMemoryLimitMb() + " MB." : "";

        if (failed > 0) {
            QuestionBankDto.CheckedTest first = results.stream()
                .filter(r -> !r.verdict().equals(Verdict.OK.name())).findFirst().orElseThrow();
            finish(row, BankQuestionCheck.Status.FAILED, failed + " of " + results.size() + " tests failed. First: "
                + (first.sample() ? "sample " : "secret test ") + first.ordinal() + ", " + first.message() + memoryNote,
                results, maxTime);
            return;
        }
        boolean ready = bank.markReady(questionId, version);
        String summary = "All " + results.size() + " tests pass"
            + (maxTime == null ? "" : ", slowest " + maxTime + " ms of " + q.getTimeLimitMs() + " ms") + "."
            + (ready ? "" : " The question changed during the check, so it is still a draft: check it again.")
            + memoryNote;
        finish(row, BankQuestionCheck.Status.PASSED, summary, results, maxTime);
    }

    /** Runs the model solution on these tests, in batches; results by test id. */
    private Map<Long, RunDto.TestResult> execute(BankQuestion q, List<BankQuestionTest> run,
                                                 Map<Long, BankQuestionTestData> data, boolean generating) {
        Map<Long, RunDto.TestResult> out = new HashMap<>();
        List<BankQuestionTest> batch = new ArrayList<>();
        long bytes = 0;
        int limit = generating ? GENERATE_BATCH_TESTS : BATCH_TESTS;
        for (BankQuestionTest t : run) {
            long size = t.getInputBytes() + (t.getAnswerBytes() == null ? 0 : t.getAnswerBytes());
            if (!batch.isEmpty() && (batch.size() >= limit || bytes + size > BATCH_BYTES)) {
                out.putAll(send(q, batch, data, generating));
                batch.clear();
                bytes = 0;
            }
            batch.add(t);
            bytes += size;
        }
        if (!batch.isEmpty()) out.putAll(send(q, batch, data, generating));
        return out;
    }

    private Map<Long, RunDto.TestResult> send(BankQuestion q, List<BankQuestionTest> batch,
                                              Map<Long, BankQuestionTestData> data, boolean generating) {
        List<RunDto.TestCase> cases = new ArrayList<>();
        int largestAnswer = 0;
        for (BankQuestionTest t : batch) {
            // Answers are compared here, with the question's own rule, so the runner only runs.
            cases.add(new RunDto.TestCase(new String(data.get(t.getTestId()).getInput(), StandardCharsets.UTF_8),
                null, String.valueOf(t.getTestId())));
            if (t.getAnswerBytes() != null) largestAnswer = Math.max(largestAnswer, t.getAnswerBytes());
        }
        int outputLimit = generating ? RunEngine.MAX_OUTPUT_LIMIT_BYTES
            : (int) Math.min(RunEngine.MAX_OUTPUT_LIMIT_BYTES, 2L * largestAnswer + 64 * 1024);
        // Twice the question's limit, so a slow solution is measured rather than only cut off.
        RunDto.RunLimits limits = new RunDto.RunLimits(
            Math.min(2L * q.getTimeLimitMs(), RunEngine.MAX_TIME_LIMIT_MS), q.getMemoryLimitMb(), outputLimit);
        RunDto.RunResponse response = runner.runWithLimits(q.getModelLanguage(), q.getModelSource(), cases, limits);
        if (response.error() != null) {
            throw new Stop(BankQuestionCheck.Status.ERROR, "The code runner could not run the check: "
                + response.error(), List.of());
        }
        if (!response.compiled()) {
            String output = response.compileOutput() == null ? "" : response.compileOutput().strip();
            if (output.length() > 800) output = output.substring(0, 800) + "…";
            throw new Stop(BankQuestionCheck.Status.FAILED, "The model solution does not compile"
                + (output.isEmpty() ? "." : ":\n" + output), List.of());
        }
        Map<Long, RunDto.TestResult> out = new HashMap<>();
        for (int i = 0; i < batch.size() && i < response.results().size(); i++) {
            out.put(batch.get(i).getTestId(), response.results().get(i));
        }
        return out;
    }

    /** One test's outcome; {@code answer} null when generating. */
    private static QuestionBankDto.CheckedTest verdict(BankQuestion q, BankQuestionTest t, RunDto.TestResult r,
                                                       byte[] answer) {
        if (r == null) {
            return checked(t, Verdict.NOT_RUN, null, "The runner returned no result for this test.");
        }
        long limit = q.getTimeLimitMs();
        long ms = r.durationMs();
        return switch (r.verdict()) {
            case TIME_LIMIT_EXCEEDED -> checked(t, Verdict.TIME_LIMIT, ms,
                "ran over " + Math.min(2 * limit, RunEngine.MAX_TIME_LIMIT_MS) + " ms; the limit is " + limit + " ms");
            case MEMORY_LIMIT_EXCEEDED -> checked(t, Verdict.MEMORY_LIMIT, ms,
                "ran out of memory (limit " + Math.min(q.getMemoryLimitMb(), RunEngine.MAX_MEMORY_LIMIT_MB) + " MB)");
            case RUNTIME_ERROR -> checked(t, Verdict.RUNTIME_ERROR, ms, "crashed"
                + (r.exitCode() == null ? "" : " with exit code " + r.exitCode()) + firstLine(r.stderr()));
            default -> {
                if (r.truncated()) {
                    yield checked(t, Verdict.OUTPUT_TOO_LARGE, ms, "printed more than the runner keeps ("
                        + (RunEngine.MAX_OUTPUT_LIMIT_BYTES >> 20) + " MB at most)");
                }
                if (ms > limit) {
                    yield checked(t, Verdict.TIME_LIMIT, ms, "took " + ms + " ms; the limit is " + limit + " ms");
                }
                if (answer != null) {
                    String diff = OutputChecker.difference(r.actual(), new String(answer, StandardCharsets.UTF_8),
                        q.getChecker(), q.getFloatTolerance());
                    if (diff != null) yield checked(t, Verdict.WRONG_ANSWER, ms, "wrong answer: " + diff);
                }
                if (ms > limit * TIME_MARGIN) {
                    yield checked(t, Verdict.CLOSE_TO_LIMIT, ms, "took " + ms + " ms, more than half the "
                        + limit + " ms limit; the judge may be slower than the runner");
                }
                yield checked(t, Verdict.OK, ms, null);
            }
        };
    }

    private static QuestionBankDto.CheckedTest checked(BankQuestionTest t, Verdict v, Long ms, String message) {
        return new QuestionBankDto.CheckedTest(t.getTestId(), t.getSample(), t.getOrdinal(), v.name(), ms, message);
    }

    private static String firstLine(String stderr) {
        if (stderr == null || stderr.isBlank()) return "";
        String line = stderr.strip().lines().findFirst().orElse("");
        return ": " + (line.length() > 160 ? line.substring(0, 157) + "…" : line);
    }

    private void finish(BankQuestionCheck row, BankQuestionCheck.Status status, String summary,
                        List<QuestionBankDto.CheckedTest> results, Integer maxTime) {
        row.setStatus(status);
        row.setFinishedAt(Instant.now());
        row.setSummary(summary.length() > 1000 ? summary.substring(0, 997) + "…" : summary);
        row.setMaxTimeMs(maxTime);
        try {
            row.setResults(json.writeValueAsString(results));
        } catch (Exception e) {
            row.setResults("[]");
        }
        checks.save(row);
    }

    private QuestionBankDto.Check view(BankQuestionCheck c) {
        List<QuestionBankDto.CheckedTest> results = List.of();
        if (c.getResults() != null) {
            try {
                results = json.readValue(c.getResults(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Unreadable results for question check {}", c.getQuestionId());
            }
        }
        String by = c.getStartedBy() == null ? null : users.findById(c.getStartedBy())
            .map(u -> StringUtils.hasText(u.getFullName()) ? u.getFullName() : u.getUsername()).orElse(null);
        return new QuestionBankDto.Check(c.getStatus(), Boolean.TRUE.equals(c.getGenerate()), c.getVersion(),
            by, c.getStartedAt(), c.getFinishedAt(), c.getSummary(), c.getMaxTimeMs(), results);
    }

}
