package com.cpintel.questions;

import com.cpintel.entity.BankQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The template people start from has to be a package the bank takes without a word. */
class PackageTemplateTest {

    @Test
    @DisplayName("The template imports with no warnings, and says what it shows")
    void importsCleanly() {
        ProblemPackage.Contents c = ProblemPackage.read(
            new ByteArrayInputStream(PackageTemplate.zip()), PackageTemplate.FILE_NAME);

        assertEquals(List.of(), c.warnings());
        assertEquals("Sum of numbers", c.title());
        assertEquals(1000, c.timeLimitMs());
        assertEquals(256, c.memoryLimitMb());
        assertEquals(BankQuestion.Checker.EXACT, c.checker());
        assertTrue(c.statementMarkdown().contains("## Input"));
        assertEquals("cpp", c.modelLanguage());
        assertEquals(2, c.tests().stream().filter(ProblemPackage.Test::sample).count());
        assertEquals(3, c.tests().stream().filter(t -> !t.sample()).count());
        assertTrue(c.tests().stream().allMatch(t -> t.answer() != null));
    }

    @Test
    @DisplayName("Its statement renders with every formula drawn")
    void statementRenders() {
        ProblemPackage.Contents c = ProblemPackage.read(
            new ByteArrayInputStream(PackageTemplate.zip()), PackageTemplate.FILE_NAME);

        StatementRenderer.Result r = new StatementRenderer().render(new StatementRenderer.Statement(
            c.title(), c.statementMarkdown(), c.timeLimitMs(), c.memoryLimitMb(), List.of()));
        assertEquals(List.of(), r.problems());
    }
}
