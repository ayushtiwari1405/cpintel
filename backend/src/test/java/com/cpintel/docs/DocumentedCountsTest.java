package com.cpintel.docs;

import com.cpintel.roadmap.RoadmapTaxonomy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The counts the README and the SRS state, held to the code.
 *
 * <p>They drifted once: both said a 35-node roadmap and 20 migrations long after there were 140
 * and 26, and nobody reading the code would have looked at the docs to notice. Now a new node or
 * migration fails the build until the sentence that counts them is updated with it.
 */
class DocumentedCountsTest {

    /** Tests run from backend/; the docs are one level up. */
    private static final Path ROOT = Path.of("..");

    private static final Pattern NODES = Pattern.compile(
        "(\\d+)(?:-node| skill-tree nodes| DSA sub-skill nodes)");
    private static final Pattern MIGRATIONS = Pattern.compile("(\\d+) versioned migrations");

    private static List<String> claims(Pattern pattern, String... files) throws IOException {
        List<String> found = new ArrayList<>();
        for (String file : files) {
            Matcher m = pattern.matcher(Files.readString(ROOT.resolve(file)));
            while (m.find()) found.add(file + ": \"" + m.group() + "\"");
        }
        return found;
    }

    @Test
    @DisplayName("every roadmap-node count in the README and SRS is the taxonomy's")
    void nodeCounts() throws IOException {
        int actual = RoadmapTaxonomy.NODES.size();
        List<String> found = claims(NODES, "README.md", "docs/SRS.md");
        assertFalse(found.isEmpty(), "the docs no longer state the node count anywhere");
        for (String claim : found) {
            assertTrue(claim.contains("\"" + actual), claim + ", but the taxonomy has " + actual);
        }
    }

    @Test
    @DisplayName("the README's migration count is the number of migrations")
    void migrationCount() throws IOException {
        long actual;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            actual = files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                .count();
        }
        List<String> found = claims(MIGRATIONS, "README.md");
        assertFalse(found.isEmpty(), "the README no longer states the migration count");
        for (String claim : found) {
            assertTrue(claim.contains("\"" + actual + " "), claim + ", but there are " + actual);
        }
    }
}
