package com.cpintel.questions;

import com.cpintel.entity.BankQuestion;
import com.cpintel.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Reading problem packages as DOMjudge would take them, and writing them back. */
class ProblemPackageTest {

    private static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static ProblemPackage.Contents read(Map<String, String> files) throws Exception {
        return ProblemPackage.read(new ByteArrayInputStream(zip(files)), "sum.zip");
    }

    private static String refusal(Map<String, String> files) {
        return assertThrows(ApiException.class, () -> read(files)).getMessage();
    }

    private static Map<String, String> files(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    @Test
    @DisplayName("A typical package: name per language, limits, float checker, Markdown, tests in natural order, a solution")
    void typical() throws Exception {
        ProblemPackage.Contents c = read(files(
            "sum/problem.yaml", "name:\n  en: Sum of two\n  de: Summe\nlimits:\n  memory: 512\nvalidation: default\nvalidator_flags: float_tolerance 1e-4\n",
            "sum/domjudge-problem.ini", "timelimit = '1.5'\n",
            "sum/problem_statement/problem.md", "Add $a$ and $b$.",
            "sum/data/sample/1.in", "1 2\n", "sum/data/sample/1.ans", "3\n",
            "sum/data/secret/group2/10.in", "x", "sum/data/secret/group2/10.ans", "x",
            "sum/data/secret/group2/2.in", "y", "sum/data/secret/group2/2.ans", "y",
            "sum/data/secret/group1/5.in", "z",
            "sum/submissions/accepted/b.py", "print(3)",
            "sum/submissions/accepted/a.cpp", "int main(){}",
            "sum/submissions/wrong_answer/w.cpp", "nope",
            "__MACOSX/sum/._problem.yaml", "junk"));

        assertEquals("Sum of two", c.title());
        assertEquals(1500, c.timeLimitMs());
        assertEquals(512, c.memoryLimitMb());
        assertEquals(BankQuestion.Checker.FLOAT, c.checker());
        assertEquals(1e-4, c.floatTolerance());
        assertEquals("Add $a$ and $b$.", c.statementMarkdown());
        assertNull(c.statementPdf());
        assertEquals("cpp", c.modelLanguage());
        assertEquals("int main(){}", c.modelSource());
        assertEquals(List.of("1", "group1/5", "group2/2", "group2/10"),
            c.tests().stream().map(ProblemPackage.Test::name).toList());
        assertTrue(c.tests().get(0).sample());
        assertNull(c.tests().get(1).answer());
        assertTrue(c.warnings().stream().anyMatch(w -> w.contains("2 accepted solutions")), c.warnings().toString());
    }

    @Test
    @DisplayName("Checker flags: none is ignore-case, case_sensitive is exact")
    void checkers() throws Exception {
        assertEquals(BankQuestion.Checker.IGNORE_CASE,
            read(files("data/secret/1.in", "1")).checker());
        assertEquals(BankQuestion.Checker.EXACT,
            read(files("problem.yaml", "validator_flags: case_sensitive\n", "data/secret/1.in", "1")).checker());
    }

    @Test
    @DisplayName("Missing pieces become defaults with a warning; the title falls back to the file name")
    void defaults() throws Exception {
        ProblemPackage.Contents c = read(files(
            "domjudge-problem.ini", "timelimit = 99\n",
            "problem_statement/problem.en.tex", "\\begin{problem}",
            "data/secret/1.in", "1"));

        assertEquals("sum", c.title());
        assertNull(c.timeLimitMs());
        assertTrue(c.warnings().stream().anyMatch(w -> w.contains("outside 0.1-20 s")));
        assertTrue(c.warnings().stream().anyMatch(w -> w.contains("LaTeX")));
    }

    @Test
    @DisplayName("Refused: a custom validator, no tests, not a zip, bad YAML")
    void refused() {
        assertTrue(refusal(files("output_validators/v/v.cpp", "x", "data/secret/1.in", "1")).contains("own validator"));
        assertTrue(refusal(files("problem.yaml", "validation: custom\n", "data/secret/1.in", "1")).contains("own validator"));
        assertTrue(refusal(files("problem.yaml", "name: x\n")).contains("no tests"));
        assertTrue(refusal(files("problem.yaml", "name: [unclosed\n", "data/secret/1.in", "1")).contains("problem.yaml"));
        String notZip = assertThrows(ApiException.class, () ->
            ProblemPackage.read(new ByteArrayInputStream("hello".getBytes()), "x.zip")).getMessage();
        assertTrue(notZip.contains("empty") || notZip.contains("not a zip"), notZip);
    }

    @Test
    @DisplayName("YAML cannot build objects: a Java type tag is refused, not instantiated")
    void safeYaml() {
        String yaml = "name: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1:1/\"]]]]\n";
        assertTrue(refusal(files("problem.yaml", yaml, "data/secret/1.in", "1")).contains("problem.yaml"));
    }

    @Test
    @DisplayName("A zip that inflates past the cap stops at the cap")
    void bomb() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("data/secret/1.in"));
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i <= ProblemPackage.MAX_INFLATED_BYTES >> 20; i++) z.write(zeros);
            z.closeEntry();
        }
        assertTrue(out.size() < 1 << 20, "the test archive should be small: " + out.size());
        String message = assertThrows(ApiException.class, () ->
            ProblemPackage.read(new ByteArrayInputStream(out.toByteArray()), "bomb.zip")).getMessage();
        assertTrue(message.contains("unpacks to more than"), message);
    }

    @Test
    @DisplayName("Too many entries are refused")
    void entries() throws Exception {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i <= ProblemPackage.MAX_ENTRIES; i++) many.put("data/secret/" + i + ".in", "1");
        assertTrue(refusal(many).contains("more than"));
    }

    @Test
    @DisplayName("Writing then reading gives the same question back")
    void roundTrip() {
        byte[] pdf = "%PDF-1.4 stand-in".getBytes();
        byte[] zip = ProblemPackage.write(new ProblemPackage.Source("Sum: \"two\"", 2500, 128,
            BankQuestion.Checker.FLOAT, 1e-6, pdf, "python3", "print(3)", List.of(
                new ProblemPackage.Test("a", true, "1 2\n".getBytes(), "3\n".getBytes()),
                new ProblemPackage.Test("b", false, "5\n".getBytes(), null))));

        ProblemPackage.Contents c = ProblemPackage.read(new ByteArrayInputStream(zip), "x.zip");
        assertEquals("Sum: \"two\"", c.title());
        assertEquals(2500, c.timeLimitMs());
        assertEquals(128, c.memoryLimitMb());
        assertEquals(BankQuestion.Checker.FLOAT, c.checker());
        assertEquals(1e-6, c.floatTolerance());
        assertArrayEquals(pdf, c.statementPdf());
        assertEquals("python3", c.modelLanguage());
        assertEquals(2, c.tests().size());
        assertEquals("01", c.tests().get(0).name());
        assertTrue(c.tests().get(0).sample());
        assertNull(c.tests().get(1).answer());
    }

    @Test
    @DisplayName("Time limits are written as DOMjudge reads them")
    void seconds() {
        assertEquals("1", ProblemPackage.seconds(1000));
        assertEquals("2.5", ProblemPackage.seconds(2500));
        assertEquals("0.125", ProblemPackage.seconds(125));
    }
}
