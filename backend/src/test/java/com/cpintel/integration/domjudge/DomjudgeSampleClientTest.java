package com.cpintel.integration.domjudge;

import com.cpintel.practice.PracticeDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading samples out of an archive, in the two shapes DOMjudge serves them: one problem's
 * samples.zip, and DOMjudge 9's contest-wide one with a folder per problem label. A mistake in
 * either is silent — the testcase console just comes up empty, and Run has nothing to run.
 */
class DomjudgeSampleClientTest {

    private static byte[] zip(String... namesAndContents) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry(namesAndContents[i]));
                out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void contestArchiveYieldsOnlyThatLabelsFolder() throws Exception {
        byte[] archive = zip(
            "A/1.in", "1 2\n", "A/1.ans", "3\n", "A/statement.pdf", "%PDF",
            "AB/1.in", "wrong\n", "AB/1.ans", "wrong\n",
            "B/1.in", "b\n", "B/1.ans", "b\n");

        List<PracticeDto.Sample> a = DomjudgeSampleClient.unzip(archive, "A");

        assertEquals(List.of(new PracticeDto.Sample("1 2\n", "3\n")), a,
            "a folder named after another label's prefix (AB) must not leak into A");
    }

    @Test
    void inputsPairWithAnswersByNameWhateverTheOrder() throws Exception {
        byte[] archive = zip("2.ans", "four\n", "1.in", "one\n", "2.in", "two\n", "1.out", "x\n");

        List<PracticeDto.Sample> all = DomjudgeSampleClient.unzip(archive, null);

        assertEquals(List.of(new PracticeDto.Sample("one\n", "x\n"),
            new PracticeDto.Sample("two\n", "four\n")), all);
    }

    @Test
    void aLabelWithNoFolderHasNoSamples() throws Exception {
        assertTrue(DomjudgeSampleClient.unzip(zip("A/1.in", "x\n"), "C").isEmpty());
        assertTrue(DomjudgeSampleClient.unzip(new byte[0], "A").isEmpty());
    }

    private static byte[] zipBytes(String name, int size, int copies) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] zeros = new byte[size];
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < copies; i++) {
                out.putNextEntry(new ZipEntry(i + name));
                out.write(zeros);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("an entry that inflates past the sample cap is refused, not read into memory")
    void oversizedEntryRefused() throws Exception {
        byte[] bomb = zipBytes(".in", DomjudgeSampleClient.MAX_SAMPLE_BYTES + 1, 1);
        assertTrue(bomb.length < 64 * 1024, "the archive itself is small");

        assertThrows(java.io.IOException.class, () -> DomjudgeSampleClient.unzip(bomb, null));
    }

    @Test
    @DisplayName("many entries under the per-file cap still stop at the archive's total")
    void totalCapped() throws Exception {
        int each = DomjudgeSampleClient.MAX_SAMPLE_BYTES;
        int copies = (int) (DomjudgeSampleClient.MAX_TOTAL_BYTES / each) + 1;

        assertThrows(java.io.IOException.class,
            () -> DomjudgeSampleClient.unzip(zipBytes(".in", each, copies), null));
    }

    @Test
    @DisplayName("an archive of countless tiny entries is refused")
    void entryCountCapped() throws Exception {
        byte[] many = zipBytes(".txt", 0, DomjudgeSampleClient.MAX_ENTRIES + 1);

        assertThrows(java.io.IOException.class, () -> DomjudgeSampleClient.unzip(many, null));
    }

    @Test
    @DisplayName("a statement past the sample cap is skipped, not refused")
    void nonSampleEntriesNotRead() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("A/statement.pdf"));
            out.write(new byte[DomjudgeSampleClient.MAX_SAMPLE_BYTES + 1]);
            out.closeEntry();
            out.putNextEntry(new ZipEntry("A/1.in"));
            out.write("x\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        assertEquals(List.of(new PracticeDto.Sample("x\n", "")),
            DomjudgeSampleClient.unzip(bytes.toByteArray(), "A"));
    }
}
