package com.cpintel.questions;

import com.cpintel.classrooms.ClassroomService;
import com.cpintel.entity.BankQuestion;
import com.cpintel.entity.BankQuestionStatementPdf;
import com.cpintel.entity.BankQuestionTest;
import com.cpintel.entity.BankQuestionTestData;
import com.cpintel.entity.Classroom;
import com.cpintel.entity.User;
import com.cpintel.evaluation.UsernameOrder;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.BankQuestionRepository;
import com.cpintel.repository.jpa.BankQuestionStatementPdfRepository;
import com.cpintel.repository.jpa.BankQuestionTestDataRepository;
import com.cpintel.repository.jpa.BankQuestionTestRepository;
import com.cpintel.repository.jpa.ClassroomRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A classroom's question bank: questions written in CPIntel, with their tests.
 *
 * <p><b>Who.</b> The classroom's admins and any of its TAs, for every question in it — whoever
 * sets a paper may be either. Everyone else is told the classroom is not theirs. An archived
 * classroom's bank can be read but not changed.
 *
 * <p><b>Edits.</b> Each change takes the question's row lock, names the version the editor
 * loaded, and is refused if somebody saved in between, so two TAs on the same question cannot
 * overwrite each other without knowing. Any change sends the question back to draft: it is
 * ready only once its model solution has been checked against the tests as they now are.
 *
 * <p><b>Tests.</b> Uploaded as {@code name.in} with an optional {@code name.ans} (or
 * {@code .out}), and appended in the natural order of their names. The bytes are stored as
 * given. Nothing here talks to the judge: publishing is a separate, admin-only step.
 */
@Service
@RequiredArgsConstructor
public class QuestionBankService {

    static final int MAX_TESTS = 200;
    static final long MAX_QUESTION_BYTES = 64L * 1024 * 1024;
    /** As spring.servlet.multipart.max-file-size; bigger files never reach this class. */
    static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    /** Samples appear in the statement and the arena, so they stay small. */
    static final int MAX_SAMPLE_BYTES = 256 * 1024;
    private static final int MAX_NAME = 120;

    private static final QuestionBankDto.Limits LIMITS = new QuestionBankDto.Limits(
        MAX_TESTS, MAX_QUESTION_BYTES, MAX_FILE_BYTES, MAX_SAMPLE_BYTES);

    private final ClassroomService classrooms;
    private final ClassroomRepository classroomRepository;
    private final BankQuestionRepository questions;
    private final BankQuestionTestRepository tests;
    private final BankQuestionTestDataRepository testData;
    private final UserRepository users;
    private final AuditService auditService;
    private final BankQuestionStatementPdfRepository statementPdfs;
    private final StatementRenderer renderer;

    // ------------------------------------------------------------------ access

    /** The classrooms whose bank this person may open: those they run, then those they TA. */
    @Transactional(readOnly = true)
    public List<QuestionBankDto.BankClassroom> myClassrooms(Long userId) {
        Map<Long, QuestionBankDto.BankClassroom> out = new LinkedHashMap<>();
        List<Long> managed = classrooms.managedIds(userId);
        List<Classroom> run = managed == null
            ? classroomRepository.findAllByOrderByNameAsc()
            : classroomRepository.findAllById(managed);
        for (Classroom c : run) {
            out.put(c.getClassroomId(), new QuestionBankDto.BankClassroom(c.getClassroomId(),
                c.getName(), QuestionBankDto.Role.ADMIN, Boolean.TRUE.equals(c.getIsActive())));
        }
        for (Classroom c : classroomRepository.findAllById(classroomRepository.taClassroomIds(userId))) {
            out.putIfAbsent(c.getClassroomId(), new QuestionBankDto.BankClassroom(c.getClassroomId(),
                c.getName(), QuestionBankDto.Role.TA, Boolean.TRUE.equals(c.getIsActive())));
        }
        return out.values().stream()
            .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
            .toList();
    }

    /** The classroom, if this person runs it or TAs in it. */
    Classroom requireBank(Long userId, Long classroomId) {
        Classroom classroom = classrooms.require(classroomId);
        if (classrooms.canManage(userId, classroom)
            || classroomRepository.isTa(classroomId, userId)) {
            return classroom;
        }
        throw ApiException.forbidden("Only the admins and TAs of \"" + classroom.getName()
            + "\" can open its question bank.");
    }

    private Classroom requireWritable(Long userId, Long classroomId) {
        Classroom classroom = requireBank(userId, classroomId);
        if (!Boolean.TRUE.equals(classroom.getIsActive())) {
            throw ApiException.conflict("\"" + classroom.getName()
                + "\" is archived, so its questions can be read but not changed.");
        }
        return classroom;
    }

    private BankQuestion requireQuestion(Long userId, Long questionId) {
        BankQuestion q = questions.findById(questionId)
            .orElseThrow(() -> ApiException.notFound("No question with id " + questionId + "."));
        requireBank(userId, q.getClassroomId());
        return q;
    }

    /**
     * Locked for an edit, with the editor's version compared. The lock comes first: read
     * before it, the row would sit in the persistence context and the locking read would hand
     * back that copy, which another save may already have overtaken.
     */
    private BankQuestion lockForEdit(Long userId, Long questionId, Integer version) {
        BankQuestion q = questions.findForUpdate(questionId)
            .orElseThrow(() -> ApiException.notFound("No question with id " + questionId + "."));
        requireWritable(userId, q.getClassroomId());
        if (version != null && !version.equals(q.getVersion())) {
            throw ApiException.conflict("Someone else saved \"" + q.getTitle()
                + "\" since you opened it. Reload it to see their changes, then make yours again.");
        }
        return q;
    }

    // ------------------------------------------------------------------ questions

    @Transactional(readOnly = true)
    public List<QuestionBankDto.QuestionSummary> list(Long userId, Long classroomId,
                                                      boolean archived) {
        requireBank(userId, classroomId);
        List<BankQuestion> rows =
            questions.findByClassroomIdAndArchivedOrderByUpdatedAtDesc(classroomId, archived);
        if (rows.isEmpty()) return List.of();

        Map<Long, long[]> counts = new HashMap<>();
        for (Object[] r : tests.countsFor(rows.stream().map(BankQuestion::getQuestionId).toList())) {
            counts.put(((Number) r[0]).longValue(), new long[] {
                ((Number) r[1]).longValue(), ((Number) r[2]).longValue(), ((Number) r[3]).longValue()});
        }
        Map<Long, String> names = names(rows.stream().map(BankQuestion::getUpdatedBy).toList());
        return rows.stream().map(q -> {
            long[] c = counts.getOrDefault(q.getQuestionId(), new long[3]);
            return new QuestionBankDto.QuestionSummary(q.getQuestionId(), q.getClassroomId(),
                q.getTitle(), q.getStatus(), (int) c[0], (int) c[1], c[2], q.getVersion(),
                Boolean.TRUE.equals(q.getArchived()), q.getUpdatedAt(), names.get(q.getUpdatedBy()));
        }).toList();
    }

    @Transactional(readOnly = true)
    public QuestionBankDto.QuestionDetail get(Long userId, Long questionId) {
        return detail(requireQuestion(userId, questionId));
    }

    @Transactional
    public QuestionBankDto.QuestionDetail create(Long userId, Long classroomId,
                                                 QuestionBankDto.QuestionRequest req,
                                                 HttpServletRequest httpReq) {
        requireWritable(userId, classroomId);
        BankQuestion q = BankQuestion.builder()
            .classroomId(classroomId)
            .createdBy(userId)
            .updatedBy(userId)
            .build();
        apply(q, req);
        q = questions.save(q);
        auditService.recordIn(classroomId, userId, AuditService.QUESTION_CREATED, "QUESTION",
            String.valueOf(q.getQuestionId()), httpReq);
        return detail(q);
    }

    @Transactional
    public QuestionBankDto.QuestionDetail update(Long userId, Long questionId,
                                                 QuestionBankDto.QuestionRequest req,
                                                 HttpServletRequest httpReq) {
        if (req.version() == null) {
            throw ApiException.badRequest("Say which version of the question you are saving.");
        }
        BankQuestion q = lockForEdit(userId, questionId, req.version());
        apply(q, req);
        q.touched(userId);
        auditService.recordIn(q.getClassroomId(), userId, AuditService.QUESTION_UPDATED, "QUESTION",
            String.valueOf(questionId), httpReq);
        return detail(q);
    }

    /** Archiving hides a question from the bank and keeps everything; restoring brings it back. */
    @Transactional
    public QuestionBankDto.QuestionDetail setArchived(Long userId, Long questionId,
                                                      boolean archived, HttpServletRequest httpReq) {
        BankQuestion q = lockForEdit(userId, questionId, null);
        if (Boolean.valueOf(archived).equals(q.getArchived())) return detail(q);
        q.setArchived(archived);
        q.setUpdatedBy(userId);
        q.setUpdatedAt(Instant.now());
        q.setVersion(q.getVersion() + 1);
        auditService.recordIn(q.getClassroomId(), userId,
            archived ? AuditService.QUESTION_ARCHIVED : AuditService.QUESTION_RESTORED,
            "QUESTION", String.valueOf(questionId), httpReq);
        return detail(q);
    }

    private static void apply(BankQuestion q, QuestionBankDto.QuestionRequest req) {
        if (req.checker() == BankQuestion.Checker.FLOAT
            && (req.floatTolerance() == null || req.floatTolerance() <= 0 || req.floatTolerance() >= 1)) {
            throw ApiException.badRequest(
                "Comparing as numbers needs a tolerance above 0 and below 1, for example 0.000001.");
        }
        boolean hasSource = StringUtils.hasText(req.modelSource());
        if (hasSource && !StringUtils.hasText(req.modelLanguage())) {
            throw ApiException.badRequest("Say which language the model solution is written in.");
        }
        q.setTitle(req.title().strip());
        q.setStatement(req.statement() == null ? "" : req.statement());
        q.setTimeLimitMs(req.timeLimitMs());
        q.setMemoryLimitMb(req.memoryLimitMb());
        q.setChecker(req.checker());
        q.setFloatTolerance(req.checker() == BankQuestion.Checker.FLOAT ? req.floatTolerance() : null);
        q.setModelLanguage(hasSource ? req.modelLanguage() : null);
        q.setModelSource(hasSource ? req.modelSource() : null);
    }

    // ------------------------------------------------------------------ tests

    /**
     * Adds tests from {@code name.in} files and their optional {@code name.ans}/{@code name.out}
     * partners, after the question's existing ones, in the natural order of the names.
     */
    @Transactional
    public QuestionBankDto.QuestionDetail addTests(Long userId, Long questionId, boolean sample,
                                                   Integer version, List<MultipartFile> files,
                                                   HttpServletRequest httpReq) {
        if (version == null) {
            throw ApiException.badRequest("Say which version of the question you are adding to.");
        }
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest("Choose at least one .in file.");
        }
        BankQuestion q = lockForEdit(userId, questionId, version);

        Map<String, Pair> pairs = pair(files);
        List<BankQuestionTest> existing = tests.findOrdered(questionId);
        if (existing.size() + pairs.size() > MAX_TESTS) {
            throw ApiException.badRequest("A question can have at most " + MAX_TESTS
                + " tests; it has " + existing.size() + " and this adds " + pairs.size() + ".");
        }
        long stored = existing.stream()
            .mapToLong(t -> t.getInputBytes() + (t.getAnswerBytes() == null ? 0 : t.getAnswerBytes()))
            .sum();
        long adding = pairs.values().stream().mapToLong(Pair::bytes).sum();
        if (stored + adding > MAX_QUESTION_BYTES) {
            throw ApiException.badRequest("A question's tests can hold at most "
                + mib(MAX_QUESTION_BYTES) + " in all; this would make " + mib(stored + adding) + ".");
        }
        if (sample) {
            for (Map.Entry<String, Pair> e : pairs.entrySet()) {
                if (e.getValue().bytes() > MAX_SAMPLE_BYTES) {
                    throw ApiException.badRequest("Sample \"" + e.getKey() + "\" is "
                        + kib(e.getValue().bytes()) + "; a sample can be at most "
                        + kib(MAX_SAMPLE_BYTES) + ", since candidates read it in the statement.");
                }
            }
        }

        int next = existing.stream().filter(t -> t.getSample() == sample)
            .mapToInt(BankQuestionTest::getOrdinal).max().orElse(0);
        for (Map.Entry<String, Pair> e : pairs.entrySet()) {
            Pair p = e.getValue();
            BankQuestionTest t = tests.save(BankQuestionTest.builder()
                .questionId(questionId)
                .sample(sample)
                .ordinal(++next)
                .name(e.getKey())
                .inputBytes(p.input().length)
                .answerBytes(p.answer() == null ? null : p.answer().length)
                .createdBy(userId)
                .build());
            testData.save(BankQuestionTestData.builder()
                .testId(t.getTestId()).input(p.input()).answer(p.answer()).build());
        }
        q.touched(userId);
        auditService.recordIn(q.getClassroomId(), userId, AuditService.QUESTION_TESTS_CHANGED,
            "QUESTION", String.valueOf(questionId), httpReq);
        return detail(q);
    }

    @Transactional
    public QuestionBankDto.QuestionDetail deleteTest(Long userId, Long questionId, Long testId,
                                                     Integer version, HttpServletRequest httpReq) {
        if (version == null) {
            throw ApiException.badRequest("Say which version of the question you are changing.");
        }
        BankQuestion q = lockForEdit(userId, questionId, version);
        BankQuestionTest gone = requireTest(questionId, testId);
        tests.delete(gone);
        tests.flush();
        // Close the gap, so positions stay 1, 2, 3 …
        int ordinal = 0;
        for (BankQuestionTest t : tests.findOrdered(questionId)) {
            if (!t.getSample().equals(gone.getSample())) continue;
            t.setOrdinal(++ordinal);
        }
        q.touched(userId);
        auditService.recordIn(q.getClassroomId(), userId, AuditService.QUESTION_TESTS_CHANGED,
            "QUESTION", String.valueOf(questionId), httpReq);
        return detail(q);
    }

    @Transactional
    public QuestionBankDto.QuestionDetail reorder(Long userId, Long questionId,
                                                  QuestionBankDto.TestOrderRequest req,
                                                  HttpServletRequest httpReq) {
        BankQuestion q = lockForEdit(userId, questionId, req.version());
        Map<Long, BankQuestionTest> group = tests.findOrdered(questionId).stream()
            .filter(t -> t.getSample().equals(req.sample()))
            .collect(Collectors.toMap(BankQuestionTest::getTestId, Function.identity()));
        Set<Long> given = new HashSet<>(req.testIds());
        if (given.size() != req.testIds().size() || !given.equals(group.keySet())) {
            throw ApiException.badRequest("List every " + (req.sample() ? "sample" : "secret test")
                + " of the question exactly once.");
        }
        int ordinal = 0;
        for (Long id : req.testIds()) group.get(id).setOrdinal(++ordinal);
        q.touched(userId);
        auditService.recordIn(q.getClassroomId(), userId, AuditService.QUESTION_TESTS_CHANGED,
            "QUESTION", String.valueOf(questionId), httpReq);
        return detail(q);
    }

    /** One test's input or answer, for the people who may edit the question. */
    @Transactional(readOnly = true)
    public QuestionBankDto.Download download(Long userId, Long questionId, Long testId,
                                             boolean answer) {
        requireQuestion(userId, questionId);
        BankQuestionTest t = requireTest(questionId, testId);
        BankQuestionTestData data = testData.findById(testId)
            .orElseThrow(() -> ApiException.notFound("That test has no data."));
        byte[] bytes = answer ? data.getAnswer() : data.getInput();
        if (bytes == null) throw ApiException.notFound("That test has no answer yet.");
        String base = (t.getSample() ? "sample-" : "secret-") + t.getOrdinal();
        return new QuestionBankDto.Download(base + (answer ? ".ans" : ".in"), bytes);
    }

    private BankQuestionTest requireTest(Long questionId, Long testId) {
        return tests.findById(testId)
            .filter(t -> t.getQuestionId().equals(questionId))
            .orElseThrow(() -> ApiException.notFound("No test with id " + testId
                + " in this question."));
    }

    // ------------------------------------------------------------------ statement

    /*
     * Rendering takes up to a second or so of CPU, so these two are not transactional: the
     * reads before it each take their own short transaction instead of holding a connection
     * open for the whole render.
     */

    /** The editor's current text rendered, unsaved, with the question's saved samples. */
    public QuestionBankDto.Rendered preview(Long userId, Long questionId,
                                            QuestionBankDto.PreviewRequest req) {
        BankQuestion q = requireQuestion(userId, questionId);
        String title = StringUtils.hasText(req.title()) ? req.title().strip() : q.getTitle();
        return rendered(renderer.render(new StatementRenderer.Statement(title,
            req.statement() == null ? "" : req.statement(), req.timeLimitMs(), req.memoryLimitMb(),
            samples(questionId))));
    }

    /** The statement as candidates would read it: the uploaded PDF, or the saved Markdown rendered. */
    public QuestionBankDto.Rendered statement(Long userId, Long questionId) {
        BankQuestion q = requireQuestion(userId, questionId);
        if (q.getStatementKind() == BankQuestion.StatementKind.PDF) {
            return statementPdfs.findById(questionId)
                .map(p -> new QuestionBankDto.Rendered(p.getPdf(), List.of()))
                .orElseThrow(() -> ApiException.notFound("The uploaded statement is missing."));
        }
        return rendered(renderer.render(new StatementRenderer.Statement(q.getTitle(),
            q.getStatement(), q.getTimeLimitMs(), q.getMemoryLimitMb(), samples(questionId))));
    }

    /** Uses the setter's own PDF as the statement, replacing any uploaded before. */
    @Transactional
    public QuestionBankDto.QuestionDetail uploadStatementPdf(Long userId, Long questionId,
                                                             Integer version, MultipartFile file,
                                                             HttpServletRequest httpReq) {
        if (version == null) {
            throw ApiException.badRequest("Say which version of the question you are changing.");
        }
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Choose a PDF.");
        BankQuestion q = lockForEdit(userId, questionId, version);
        byte[] bytes = bytes(file);
        int pages = StatementPdfCheck.pages(bytes);
        String name = baseName(file.getOriginalFilename());
        if (name.isEmpty()) name = "statement.pdf";
        if (name.length() > 200) name = name.substring(name.length() - 200);

        BankQuestionStatementPdf row = statementPdfs.findById(questionId)
            .orElseGet(() -> BankQuestionStatementPdf.builder().questionId(questionId).build());
        row.setPdf(bytes);
        row.setFileName(name);
        row.setSizeBytes(bytes.length);
        row.setPages(pages);
        row.setUploadedBy(userId);
        row.setUploadedAt(Instant.now());
        statementPdfs.save(row);

        q.setStatementKind(BankQuestion.StatementKind.PDF);
        q.touched(userId);
        auditService.recordIn(q.getClassroomId(), userId, AuditService.QUESTION_UPDATED, "QUESTION",
            String.valueOf(questionId), httpReq);
        return detail(q);
    }

    /** Back to the written statement; the uploaded PDF is removed. */
    @Transactional
    public QuestionBankDto.QuestionDetail removeStatementPdf(Long userId, Long questionId,
                                                             Integer version,
                                                             HttpServletRequest httpReq) {
        if (version == null) {
            throw ApiException.badRequest("Say which version of the question you are changing.");
        }
        BankQuestion q = lockForEdit(userId, questionId, version);
        statementPdfs.findById(questionId).ifPresent(statementPdfs::delete);
        q.setStatementKind(BankQuestion.StatementKind.MARKDOWN);
        q.touched(userId);
        auditService.recordIn(q.getClassroomId(), userId, AuditService.QUESTION_UPDATED, "QUESTION",
            String.valueOf(questionId), httpReq);
        return detail(q);
    }

    // ------------------------------------------------------------------ packages

    /**
     * A new draft question from a problem package. The archive is read in full and checked
     * against the bank's limits before anything is written, so a refused package leaves nothing.
     */
    @Transactional
    public QuestionBankDto.ImportResult importPackage(Long userId, Long classroomId,
                                                      java.io.InputStream zip, String fileName,
                                                      HttpServletRequest httpReq) {
        requireWritable(userId, classroomId);
        ProblemPackage.Contents p = ProblemPackage.read(zip, fileName);
        List<String> warnings = new ArrayList<>(p.warnings());

        if (p.tests().size() > MAX_TESTS) {
            throw ApiException.badRequest("A question can have at most " + MAX_TESTS
                + " tests; the package has " + p.tests().size() + ".");
        }
        long bytes = p.tests().stream()
            .mapToLong(t -> t.input().length + (t.answer() == null ? 0L : t.answer().length)).sum();
        if (bytes > MAX_QUESTION_BYTES) {
            throw ApiException.badRequest("A question's tests can hold at most " + mib(MAX_QUESTION_BYTES)
                + " in all; the package's come to " + mib(bytes) + ".");
        }
        for (ProblemPackage.Test t : p.tests()) {
            long size = t.input().length + (t.answer() == null ? 0L : t.answer().length);
            if (t.sample() && size > MAX_SAMPLE_BYTES) {
                throw ApiException.badRequest("Sample \"" + t.name() + "\" is " + kib(size)
                    + "; a sample can be at most " + kib(MAX_SAMPLE_BYTES) + ".");
            }
        }
        int missing = (int) p.tests().stream().filter(t -> t.answer() == null).count();
        if (missing > 0) {
            warnings.add(missing + " test" + (missing == 1 ? " has" : "s have") + " no answer.");
        }

        Integer pages = null;
        if (p.statementPdf() != null) {
            try {
                pages = StatementPdfCheck.pages(p.statementPdf());
            } catch (ApiException e) {
                warnings.add("The statement PDF was not kept: " + e.getMessage());
            }
        }

        BankQuestion q = BankQuestion.builder()
            .classroomId(classroomId)
            .title(p.title())
            .statement(p.statementMarkdown() == null ? "" : StatementRenderer.clean(p.statementMarkdown()))
            .timeLimitMs(p.timeLimitMs() == null ? 1000 : p.timeLimitMs())
            .memoryLimitMb(p.memoryLimitMb() == null ? 256 : p.memoryLimitMb())
            .checker(p.checker())
            .floatTolerance(p.floatTolerance())
            .modelLanguage(p.modelLanguage())
            .modelSource(p.modelSource())
            .statementKind(pages != null ? BankQuestion.StatementKind.PDF : BankQuestion.StatementKind.MARKDOWN)
            .createdBy(userId)
            .updatedBy(userId)
            .build();
        q = questions.save(q);

        int sample = 0;
        int secret = 0;
        for (ProblemPackage.Test t : p.tests()) {
            BankQuestionTest row = tests.save(BankQuestionTest.builder()
                .questionId(q.getQuestionId())
                .sample(t.sample())
                .ordinal(t.sample() ? ++sample : ++secret)
                .name(t.name())
                .inputBytes(t.input().length)
                .answerBytes(t.answer() == null ? null : t.answer().length)
                .createdBy(userId)
                .build());
            testData.save(BankQuestionTestData.builder()
                .testId(row.getTestId()).input(t.input()).answer(t.answer()).build());
        }
        if (pages != null) {
            statementPdfs.save(BankQuestionStatementPdf.builder()
                .questionId(q.getQuestionId())
                .pdf(p.statementPdf())
                .fileName("problem.pdf")
                .sizeBytes(p.statementPdf().length)
                .pages(pages)
                .uploadedBy(userId)
                .uploadedAt(Instant.now())
                .build());
        }
        auditService.recordIn(classroomId, userId, AuditService.QUESTION_IMPORTED, "QUESTION",
            String.valueOf(q.getQuestionId()), httpReq);
        return new QuestionBankDto.ImportResult(detail(q), List.copyOf(warnings));
    }

    /** The question as a package: the bytes publishing will send to the judge. */
    public QuestionBankDto.Download exportPackage(Long userId, Long questionId) {
        BankQuestion q = requireQuestion(userId, questionId);
        byte[] statement = statement(userId, questionId).pdf();
        List<ProblemPackage.Test> all = new ArrayList<>();
        List<BankQuestionTest> ordered = tests.findOrdered(questionId);
        Map<Long, BankQuestionTestData> data = testData.findAllById(
                ordered.stream().map(BankQuestionTest::getTestId).toList()).stream()
            .collect(Collectors.toMap(BankQuestionTestData::getTestId, Function.identity()));
        for (BankQuestionTest t : ordered) {
            BankQuestionTestData d = data.get(t.getTestId());
            if (d != null) all.add(new ProblemPackage.Test(t.getName(), t.getSample(), d.getInput(), d.getAnswer()));
        }
        byte[] zip = ProblemPackage.write(new ProblemPackage.Source(q.getTitle(), q.getTimeLimitMs(),
            q.getMemoryLimitMb(), q.getChecker(), q.getFloatTolerance(), statement, q.getModelLanguage(),
            q.getModelSource(), all));
        return new QuestionBankDto.Download(packageName(q.getTitle()), zip);
    }

    public static final String PACKAGE_TEMPLATE_NAME = PackageTemplate.FILE_NAME;

    /** The example package, the same for everyone; nothing to check access for. */
    public byte[] packageTemplate() {
        return PackageTemplate.zip();
    }

        /** A file name from a title: letters, digits and dashes. */
    static String packageName(String title) {
        String slug = java.text.Normalizer.normalize(title == null ? "" : title, java.text.Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (slug.length() > 60) slug = slug.substring(0, 60).replaceAll("-+$", "");
        return (slug.isEmpty() ? "question" : slug) + ".zip";
    }

    private List<StatementRenderer.Sample> samples(Long questionId) {
        List<BankQuestionTest> shown = tests.findOrdered(questionId).stream()
            .filter(BankQuestionTest::getSample).toList();
        if (shown.isEmpty()) return List.of();
        Map<Long, BankQuestionTestData> data = testData.findAllById(
                shown.stream().map(BankQuestionTest::getTestId).toList()).stream()
            .collect(Collectors.toMap(BankQuestionTestData::getTestId, Function.identity()));
        List<StatementRenderer.Sample> out = new ArrayList<>();
        for (BankQuestionTest t : shown) {
            BankQuestionTestData d = data.get(t.getTestId());
            if (d == null) continue;
            out.add(new StatementRenderer.Sample(StatementRenderer.utf8(d.getInput()),
                StatementRenderer.utf8(d.getAnswer())));
        }
        return out;
    }

    private static QuestionBankDto.Rendered rendered(StatementRenderer.Result r) {
        return new QuestionBankDto.Rendered(r.pdf(), r.problems());
    }

    // ------------------------------------------------------------------ uploads

    private record Pair(byte[] input, byte[] answer) {
        long bytes() { return input.length + (answer == null ? 0L : answer.length); }
    }

    /** Matches {@code x.in} with {@code x.ans} or {@code x.out}, ordered naturally by x. */
    static Map<String, Pair> pair(Collection<MultipartFile> files) {
        Map<String, byte[]> inputs = new HashMap<>();
        Map<String, byte[]> answers = new HashMap<>();
        for (MultipartFile f : files) {
            String name = baseName(f.getOriginalFilename());
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : "";
            String ext = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
            if (stem.isEmpty() || stem.length() > MAX_NAME) {
                throw ApiException.badRequest("\"" + name + "\" needs a name before its extension, "
                    + "of at most " + MAX_NAME + " characters.");
            }
            Map<String, byte[]> into = switch (ext) {
                case "in" -> inputs;
                case "ans", "out" -> answers;
                default -> throw ApiException.badRequest("\"" + name
                    + "\" is not a test file. Upload inputs as .in and answers as .ans or .out.");
            };
            if (into.put(stem, bytes(f)) != null) {
                throw ApiException.badRequest("Two files give the " + (into == inputs ? "input" : "answer")
                    + " for test \"" + stem + "\".");
            }
        }
        for (String stem : answers.keySet()) {
            if (!inputs.containsKey(stem)) {
                throw ApiException.badRequest("\"" + stem + "\" has an answer but no .in file.");
            }
        }
        Map<String, Pair> out = new TreeMap<>(UsernameOrder.NATURAL);
        inputs.forEach((stem, in) -> out.put(stem, new Pair(in, answers.get(stem))));
        return out;
    }

    /** The last path segment, whatever separator the browser used. */
    private static String baseName(String original) {
        if (original == null) return "";
        String n = original.replace('\\', '/');
        return n.substring(n.lastIndexOf('/') + 1).strip();
    }

    private static byte[] bytes(MultipartFile f) {
        try {
            return f.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read \"" + f.getOriginalFilename() + "\".");
        }
    }

    // ------------------------------------------------------------------ shaping

    private QuestionBankDto.QuestionDetail detail(BankQuestion q) {
        List<QuestionBankDto.TestInfo> list = new ArrayList<>();
        for (BankQuestionTest t : tests.findOrdered(q.getQuestionId())) {
            list.add(new QuestionBankDto.TestInfo(t.getTestId(), t.getSample(), t.getOrdinal(),
                t.getName(), t.getInputBytes(), t.getAnswerBytes()));
        }
        var pdf = statementPdfs.findInfo(q.getQuestionId()).orElse(null);
        Map<Long, String> names = names(List.of(
            Objects.requireNonNullElse(q.getCreatedBy(), 0L),
            Objects.requireNonNullElse(q.getUpdatedBy(), 0L),
            pdf == null || pdf.getUploadedBy() == null ? 0L : pdf.getUploadedBy()));
        QuestionBankDto.StatementPdfInfo pdfInfo = pdf == null ? null
            : new QuestionBankDto.StatementPdfInfo(pdf.getFileName(), pdf.getSizeBytes(), pdf.getPages(),
                pdf.getUploadedAt(), names.get(pdf.getUploadedBy()));
        return new QuestionBankDto.QuestionDetail(q.getQuestionId(), q.getClassroomId(), q.getTitle(),
            q.getStatement(), q.getStatementKind(), pdfInfo, q.getTimeLimitMs(), q.getMemoryLimitMb(), q.getChecker(),
            q.getFloatTolerance(), q.getModelLanguage(), q.getModelSource(), q.getStatus(),
            q.getVersion(), Boolean.TRUE.equals(q.getArchived()), q.getCreatedAt(),
            names.get(q.getCreatedBy()), q.getUpdatedAt(), names.get(q.getUpdatedBy()), list, LIMITS);
    }

    private Map<Long, String> names(Collection<Long> ids) {
        Set<Long> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty()) return Map.of();
        Map<Long, String> out = new HashMap<>();
        for (User u : users.findAllById(wanted)) {
            out.put(u.getUserId(), StringUtils.hasText(u.getFullName()) ? u.getFullName() : u.getUsername());
        }
        return out;
    }

    private static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String kib(long bytes) {
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
    }
}
