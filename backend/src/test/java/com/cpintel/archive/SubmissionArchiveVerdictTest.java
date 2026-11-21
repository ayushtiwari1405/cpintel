package com.cpintel.archive;

import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.repository.mongo.CodeSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionArchiveVerdictTest {

    private CodeSubmissionRepository repo;
    private SubmissionArchive archive;

    @BeforeEach
    void setUp() {
        repo = mock(CodeSubmissionRepository.class);
        archive = new SubmissionArchive(repo, null, null, null, null);
    }

    private void rowIs(CodeSubmission row) {
        when(repo.findByUserIdAndPlatformAndContestIdAndExternalId(1L, "DOMJUDGE", "c", 9L))
            .thenReturn(Optional.ofNullable(row));
    }

    @Test
    @DisplayName("a row that exists and moves on is saved, and reported as in step")
    void movesOn() {
        CodeSubmission row = new CodeSubmission();
        row.setVerdict("TESTING");
        rowIs(row);
        assertThat(archive.updateVerdict(1L, "DOMJUDGE", "c", 9L, "OK", null, 12, null)).isTrue();
        assertThat(row.getVerdict()).isEqualTo("OK");
        verify(repo).save(row);
    }

    @Test
    @DisplayName("a row already carrying the verdict is reported in step without a write")
    void alreadyThere() {
        CodeSubmission row = new CodeSubmission();
        row.setVerdict("OK");
        rowIs(row);
        assertThat(archive.updateVerdict(1L, "DOMJUDGE", "c", 9L, "OK", null, 12, null)).isTrue();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("an attempt not yet linked to its judge id is reported as not archived, so the poll tries again")
    void notLinkedYet() {
        rowIs(null);
        assertThat(archive.updateVerdict(1L, "DOMJUDGE", "c", 9L, "OK", null, 12, null)).isFalse();
    }
}
