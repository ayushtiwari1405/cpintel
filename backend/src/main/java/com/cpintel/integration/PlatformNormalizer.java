package com.cpintel.integration;

import com.cpintel.entity.mongo.CfSubmission;
import com.cpintel.integration.codeforces.CfModels;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PlatformNormalizer {

    public CfSubmission normalizeCf(Long userId, CfModels.Submission s) {
        return CfSubmission.builder()
            .userId(userId)
            .cfSubmissionId(s.getId())
            .contestId(s.getContestId() != null ? s.getContestId().intValue() : null)
            .problemIndex(s.getProblem() != null ? s.getProblem().getIndex() : null)
            .problemName(s.getProblem() != null ? s.getProblem().getName() : null)
            .problemRating(s.getProblem() != null ? s.getProblem().getRating() : null)
            .tags(s.getProblem() != null ? s.getProblem().getTags() : List.of())
            .verdict(s.getVerdict())
            .language(s.getProgrammingLanguage())
            .timeConsumedMs(s.getTimeConsumedMillis())
            .memoryConsumedBytes(s.getMemoryConsumedBytes())
            .submittedAt(s.getCreationTimeSeconds() != null
                ? Instant.ofEpochSecond(s.getCreationTimeSeconds()) : null)
            .createdAt(Instant.now())
            .normalized(true)
            .build();
    }
}
