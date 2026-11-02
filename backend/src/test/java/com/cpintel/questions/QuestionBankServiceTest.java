package com.cpintel.questions;

import com.cpintel.classrooms.ClassroomService;
import com.cpintel.entity.BankQuestion;
import com.cpintel.entity.BankQuestionStatementPdf;
import com.cpintel.entity.BankQuestionTest;
import com.cpintel.entity.BankQuestionTestData;
import com.cpintel.entity.Classroom;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.BankQuestionRepository;
import com.cpintel.repository.jpa.BankQuestionStatementPdfRepository;
import com.cpintel.repository.jpa.BankQuestionTestDataRepository;
import com.cpintel.repository.jpa.BankQuestionTestRepository;
import com.cpintel.repository.jpa.ClassroomRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Who may use a classroom's bank, and what an edit or an upload is allowed to do. */
class QuestionBankServiceTest {

    private static final long ADMIN = 1L;
    private static final long TA = 2L;
    private static final long STRANGER = 3L;
    private static final long ROOM = 5L;
    private static final long Q = 40L;

    private final ClassroomService classrooms = mock(ClassroomService.class);
    private final ClassroomRepository classroomRepository = mock(ClassroomRepository.class);
    private final BankQuestionRepository questions = mock(BankQuestionRepository.class);
    private final BankQuestionTestRepository tests = mock(BankQuestionTestRepository.class);
    private final BankQuestionTestDataRepository testData = mock(BankQuestionTestDataRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final BankQuestionStatementPdfRepository pdfs = mock(BankQuestionStatementPdfRepository.class);
    private final StatementRenderer renderer = mock(StatementRenderer.class);
    private QuestionBankService service;

    private Classroom room;
    private BankQuestion question;
    private final List<BankQuestionTest> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new QuestionBankService(classrooms, classroomRepository, questions, tests,
            testData, mock(UserRepository.class), audit, pdfs, renderer);
        when(pdfs.findInfo(anyLong())).thenReturn(Optional.empty());
        room = Classroom.builder().classroomId(ROOM).name("DSA").isActive(true).build();
        when(classrooms.require(ROOM)).thenReturn(room);
        when(classrooms.canManage(eq(ADMIN), any())).thenReturn(true);
        when(classroomRepository.isTa(ROOM, TA)).thenReturn(true);

        question = BankQuestion.builder().questionId(Q).classroomId(ROOM).title("Sum")
            .version(3).build();
        when(questions.findById(Q)).thenReturn(Optional.of(question));
        when(questions.findForUpdate(Q)).thenReturn(Optional.of(question));
        when(questions.save(any())).thenAnswer(inv -> {
            BankQuestion b = inv.getArgument(0);
            b.setQuestionId(Q);
            return b;
        });
        when(tests.findOrdered(Q)).thenAnswer(inv -> List.copyOf(stored));
        when(tests.save(any())).thenAnswer(inv -> {
            BankQuestionTest t = inv.getArgument(0);
            t.setTestId(100L + stored.size());
            stored.add(t);
            return t;
        });
    }

    private static QuestionBankDto.QuestionRequest request(Integer version) {
        return new QuestionBankDto.QuestionRequest("Sum of two", "Add them.", 1000, 256,
            BankQuestion.Checker.EXACT, null, "cpp", "int main(){}", version);
    }

    private static MultipartFile file(String name, String body) {
        return new MockMultipartFile("files", name, "application/octet-stream",
            body.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpStatus status(Runnable r) {
        return assertThrows(ApiException.class, r::run).getStatus();
    }

    // ------------------------------------------------------------------ access

    @Test
    @DisplayName("A classroom's admins and TAs may open its bank; anyone else is refused")
    void access() {
        assertDoesNotThrow(() -> service.requireBank(ADMIN, ROOM));
        assertDoesNotThrow(() -> service.requireBank(TA, ROOM));
        assertEquals(HttpStatus.FORBIDDEN, status(() -> service.requireBank(STRANGER, ROOM)));
        assertEquals(HttpStatus.FORBIDDEN, status(() -> service.get(STRANGER, Q)));
        assertEquals(HttpStatus.FORBIDDEN,
            status(() -> service.update(STRANGER, Q, request(3), null)));
    }

    @Test
    @DisplayName("A TA can write a question, and the write is audited in the classroom")
    void taCreates() {
        QuestionBankDto.QuestionDetail d = service.create(TA, ROOM, request(null), null);

        assertEquals("Sum of two", d.title());
        assertEquals(BankQuestion.Status.DRAFT, d.status());
        verify(audit).recordIn(eq(ROOM), eq(TA), eq(AuditService.QUESTION_CREATED), eq("QUESTION"),
            eq(String.valueOf(Q)), any());
    }

    @Test
    @DisplayName("An archived classroom's bank is read-only")
    void archivedClassroomReadOnly() {
        room.setIsActive(false);

        assertDoesNotThrow(() -> service.get(TA, Q));
        assertEquals(HttpStatus.CONFLICT, status(() -> service.update(TA, Q, request(3), null)));
        assertEquals(HttpStatus.CONFLICT, status(() -> service.create(ADMIN, ROOM, request(null), null)));
    }

    // ------------------------------------------------------------------ edits

    @Test
    @DisplayName("A save from an older version is refused; a current one bumps the version and resets to draft")
    void versions() {
        question.setStatus(BankQuestion.Status.READY);

        assertEquals(HttpStatus.CONFLICT, status(() -> service.update(TA, Q, request(2), null)));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.update(TA, Q, request(null), null)));

        QuestionBankDto.QuestionDetail d = service.update(TA, Q, request(3), null);
        assertEquals(4, d.version());
        assertEquals(BankQuestion.Status.DRAFT, d.status());
        assertEquals(TA, question.getUpdatedBy());
    }

    @Test
    @DisplayName("Comparing as numbers needs a tolerance strictly between 0 and 1; other checkers drop it")
    void floatTolerance() {
        var noTolerance = new QuestionBankDto.QuestionRequest("X", "", 1000, 256,
            BankQuestion.Checker.FLOAT, null, null, null, 3);
        var huge = new QuestionBankDto.QuestionRequest("X", "", 1000, 256,
            BankQuestion.Checker.FLOAT, 1.5, null, null, 3);
        var exact = new QuestionBankDto.QuestionRequest("X", "", 1000, 256,
            BankQuestion.Checker.EXACT, 1e-6, null, null, 3);

        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.update(TA, Q, noTolerance, null)));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.update(TA, Q, huge, null)));
        service.update(TA, Q, exact, null);
        assertNull(question.getFloatTolerance());
    }

    @Test
    @DisplayName("A model solution needs its language")
    void modelLanguage() {
        var noLanguage = new QuestionBankDto.QuestionRequest("X", "", 1000, 256,
            BankQuestion.Checker.EXACT, null, null, "print(1)", 3);

        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.update(TA, Q, noLanguage, null)));
    }

    // ------------------------------------------------------------------ uploads

    @Test
    @DisplayName("Inputs pair with .ans or .out answers and sort naturally; a missing answer is allowed")
    void pairing() {
        var pairs = QuestionBankService.pair(List.of(
            file("10.in", "x"), file("2.in", "y"), file("2.ans", "Y"), file("dir/1.in", "z"),
            file("1.out", "Z")));

        assertEquals(List.of("1", "2", "10"), new ArrayList<>(pairs.keySet()));
    }

    @Test
    @DisplayName("Uploads refuse stray answers, duplicates and files that are not tests")
    void pairingErrors() {
        assertEquals(HttpStatus.BAD_REQUEST,
            status(() -> QuestionBankService.pair(List.of(file("1.ans", "a")))));
        assertEquals(HttpStatus.BAD_REQUEST,
            status(() -> QuestionBankService.pair(List.of(file("1.in", "a"), file("x/1.in", "b")))));
        assertEquals(HttpStatus.BAD_REQUEST,
            status(() -> QuestionBankService.pair(List.of(file("notes.txt", "a")))));
        assertEquals(HttpStatus.BAD_REQUEST,
            status(() -> QuestionBankService.pair(List.of(file(".in", "a")))));
    }

    @Test
    @DisplayName("Added tests go after the existing ones, with their bytes stored apart")
    void addTests() {
        stored.add(BankQuestionTest.builder().testId(99L).questionId(Q).sample(false).ordinal(1)
            .name("old").inputBytes(1).answerBytes(1).build());

        QuestionBankDto.QuestionDetail d = service.addTests(TA, Q, false, 3,
            List.of(file("b.in", "2 2"), file("b.ans", "4"), file("a.in", "1 1")), null);

        assertEquals(List.of("old", "a", "b"), d.tests().stream().map(QuestionBankDto.TestInfo::name).toList());
        assertEquals(List.of(1, 2, 3), d.tests().stream().map(QuestionBankDto.TestInfo::ordinal).toList());
        assertNull(d.tests().get(1).answerBytes());
        verify(testData, times(2)).save(any(BankQuestionTestData.class));
        assertEquals(4, question.getVersion());
    }

    @Test
    @DisplayName("Samples are capped small, and a stale version cannot add tests")
    void addTestLimits() {
        String big = "x".repeat(QuestionBankService.MAX_SAMPLE_BYTES + 1);

        assertEquals(HttpStatus.BAD_REQUEST,
            status(() -> service.addTests(TA, Q, true, 3, List.of(file("1.in", big)), null)));
        assertDoesNotThrow(() -> service.addTests(TA, Q, false, 3, List.of(file("1.in", big)), null));
        assertEquals(HttpStatus.CONFLICT,
            status(() -> service.addTests(TA, Q, false, 3, List.of(file("2.in", "1")), null)));
    }

    @Test
    @DisplayName("A question holds at most the test limit")
    void testCountLimit() {
        for (int i = 0; i < QuestionBankService.MAX_TESTS; i++) {
            stored.add(BankQuestionTest.builder().testId((long) i).questionId(Q).sample(false)
                .ordinal(i + 1).name("t" + i).inputBytes(1).build());
        }

        assertEquals(HttpStatus.BAD_REQUEST,
            status(() -> service.addTests(TA, Q, false, 3, List.of(file("x.in", "1")), null)));
    }

    @Test
    @DisplayName("A reorder must name every test of the group once")
    void reorder() {
        BankQuestionTest a = BankQuestionTest.builder().testId(7L).questionId(Q).sample(false)
            .ordinal(1).name("a").inputBytes(1).build();
        BankQuestionTest b = BankQuestionTest.builder().testId(8L).questionId(Q).sample(false)
            .ordinal(2).name("b").inputBytes(1).build();
        stored.addAll(List.of(a, b));

        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.reorder(TA, Q,
            new QuestionBankDto.TestOrderRequest(false, List.of(7L), 3), null)));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.reorder(TA, Q,
            new QuestionBankDto.TestOrderRequest(false, List.of(7L, 7L), 3), null)));

        service.reorder(TA, Q, new QuestionBankDto.TestOrderRequest(false, List.of(8L, 7L), 3), null);
        assertEquals(Map.of(7L, 2, 8L, 1), Map.of(7L, a.getOrdinal(), 8L, b.getOrdinal()));
    }

    @Test
    @DisplayName("A test is downloaded only through its own question")
    void downloadScope() {
        when(tests.findById(55L)).thenReturn(Optional.of(BankQuestionTest.builder().testId(55L)
            .questionId(999L).sample(false).ordinal(1).name("x").inputBytes(1).build()));

        assertEquals(HttpStatus.NOT_FOUND, status(() -> service.download(TA, Q, 55L, false)));
        assertEquals(HttpStatus.FORBIDDEN, status(() -> service.download(STRANGER, Q, 55L, false)));
    }

    // ------------------------------------------------------------------ statement

    private static byte[] blankPdf() throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument();
             var out = new java.io.ByteArrayOutputStream()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("An uploaded PDF becomes the statement; removing it goes back to the written one")
    void statementPdf() throws Exception {
        var pdf = new MockMultipartFile("file", "dir/Sum.pdf", "application/pdf", blankPdf());

        QuestionBankDto.QuestionDetail d = service.uploadStatementPdf(TA, Q, 3, pdf, null);
        assertEquals(BankQuestion.StatementKind.PDF, d.statementKind());
        assertEquals(4, d.version());
        verify(pdfs).save(argThat(p -> p.getFileName().equals("Sum.pdf") && p.getPages() == 1
            && p.getUploadedBy() == TA));

        when(pdfs.findById(Q)).thenReturn(Optional.of(BankQuestionStatementPdf.builder().questionId(Q).build()));
        d = service.removeStatementPdf(TA, Q, 4, null);
        assertEquals(BankQuestion.StatementKind.MARKDOWN, d.statementKind());
        verify(pdfs).delete(any());
    }

    @Test
    @DisplayName("Only a real PDF is accepted, and only by someone who may edit, at the current version")
    void statementPdfRefusals() throws Exception {
        var notPdf = new MockMultipartFile("file", "x.pdf", "application/pdf", "hello".getBytes());
        var pdf = new MockMultipartFile("file", "x.pdf", "application/pdf", blankPdf());

        assertEquals(HttpStatus.BAD_REQUEST, status(() -> service.uploadStatementPdf(TA, Q, 3, notPdf, null)));
        assertEquals(HttpStatus.CONFLICT, status(() -> service.uploadStatementPdf(TA, Q, 2, pdf, null)));
        assertEquals(HttpStatus.FORBIDDEN, status(() -> service.uploadStatementPdf(STRANGER, Q, 3, pdf, null)));
        verify(pdfs, never()).save(any());
    }

    @Test
    @DisplayName("The saved statement of a PDF question is the uploaded file, not a render")
    void statementServed() {
        question.setStatementKind(BankQuestion.StatementKind.PDF);
        byte[] bytes = {1, 2, 3};
        when(pdfs.findById(Q)).thenReturn(Optional.of(
            BankQuestionStatementPdf.builder().questionId(Q).pdf(bytes).build()));

        assertArrayEquals(bytes, service.statement(TA, Q).pdf());
        verifyNoInteractions(renderer);
        assertEquals(HttpStatus.FORBIDDEN, status(() -> service.statement(STRANGER, Q)));
    }

    @Test
    @DisplayName("A preview renders the editor's text with the question's saved samples")
    void previewUsesSamples() {
        stored.add(BankQuestionTest.builder().testId(9L).questionId(Q).sample(true).ordinal(1)
            .name("1").inputBytes(3).answerBytes(1).build());
        when(testData.findAllById(any())).thenReturn(List.of(BankQuestionTestData.builder()
            .testId(9L).input("1 2".getBytes()).answer("3".getBytes()).build()));
        when(renderer.render(any())).thenReturn(new StatementRenderer.Result(new byte[] {7}, List.of("p")));

        QuestionBankDto.Rendered r = service.preview(TA, Q,
            new QuestionBankDto.PreviewRequest(null, "Typed, not saved", 2000, 128));

        assertEquals(List.of("p"), r.problems());
        verify(renderer).render(argThat(s -> s.markdown().equals("Typed, not saved") && s.title().equals("Sum")
            && s.timeLimitMs() == 2000 && s.samples().size() == 1 && s.samples().get(0).answer().equals("3")));
    }
}
