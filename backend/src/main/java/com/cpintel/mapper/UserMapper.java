package com.cpintel.mapper;

import com.cpintel.dto.*;
import com.cpintel.entity.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class UserMapper {

    public UserDto.Profile toProfile(User user) {
        if (user == null) return null;

        List<PlatformDto.Summary> platforms = user.getPlatformAccounts() == null ? List.of() :
            user.getPlatformAccounts().stream()
                .map(this::toPlatformSummary)
                .collect(Collectors.toList());

        return UserDto.Profile.builder()
            .userId(user.getUserId())
            .username(user.getUsername())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .avatarUrl(user.getAvatarUrl())
            .country(user.getCountry())
            .institution(user.getInstitution())
            .role(user.getRole())
            .isVerified(user.getIsVerified())
            .createdAt(user.getCreatedAt())
            .platforms(platforms)
            .unifiedScore(toUnifiedScoreDto(user.getUnifiedScore()))
            .build();
    }

    public PlatformDto.Summary toPlatformSummary(PlatformAccount pa) {
        if (pa == null) return null;
        return PlatformDto.Summary.builder()
            .accountId(pa.getAccountId())
            .platform(pa.getPlatform())
            .handle(pa.getHandle())
            .currentRating(pa.getCurrentRating())
            .maxRating(pa.getMaxRating())
            .lastSyncedAt(pa.getLastSyncedAt())
            .syncStatus(pa.getSyncStatus())
            .build();
    }

    public UnifiedScoreDto toUnifiedScoreDto(UnifiedScore us) {
        if (us == null) return null;
        return UnifiedScoreDto.builder()
            .cfScore(us.getCfScore())
            .lcScore(us.getLcScore())
            .ccScore(us.getCcScore())
            .unifiedScore(us.getUnifiedScore())
            .cfWeight(us.getCfWeight())
            .lcWeight(us.getLcWeight())
            .ccWeight(us.getCcWeight())
            .computedAt(us.getComputedAt())
            .build();
    }

    public TopicMasteryDto toTopicMasteryDto(TopicMastery tm) {
        if (tm == null) return null;
        return TopicMasteryDto.builder()
            .masteryId(tm.getMasteryId())
            .topic(tm.getTopic())
            .masteryScore(tm.getMasteryScore())
            .confidenceScore(tm.getConfidenceScore())
            .revisionScore(tm.getRevisionScore())
            .decayScore(tm.getDecayScore())
            .problemsSolved(tm.getProblemsSolved())
            .problemsAttempted(tm.getProblemsAttempted())
            .lastPracticedAt(tm.getLastPracticedAt())
            .masteryBand(TopicMastery.MasteryBand.from(tm.getMasteryScore()).name())
            .build();
    }

    public ContestSummaryDto toContestSummaryDto(ContestSummary cs) {
        if (cs == null) return null;
        return ContestSummaryDto.builder()
            .contestId(cs.getContestId())
            .platform(cs.getPlatform())
            .contestName(cs.getContestName())
            .rank(cs.getRank())
            .ratingBefore(cs.getRatingBefore())
            .ratingAfter(cs.getRatingAfter())
            .ratingChange(cs.getRatingChange())
            .problemsSolved(cs.getProblemsSolved())
            .totalProblems(cs.getTotalProblems())
            .firstSolveMins(cs.getFirstSolveMins())
            .wrongSubmissions(cs.getWrongSubmissions())
            .contestDate(cs.getContestDate())
            .build();
    }
}
