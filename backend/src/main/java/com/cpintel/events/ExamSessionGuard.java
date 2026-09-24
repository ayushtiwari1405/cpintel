package com.cpintel.events;

import com.cpintel.entity.GroupContest;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.security.SessionMode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Keeps ordinary sessions out of examinations, and examination sessions inside theirs.
 *
 * <p>The judge contest behind an examination is an ordinary DOMjudge contest as far as the
 * compete arena is concerned, and it can be opened from the Contests tab like any other. So the
 * rule is applied to the contest, not to the screen: while a paper has not ended, its contest is
 * reachable from an examination session for that paper and from nothing else. Once it ends the
 * paper is over and ordinary sessions may look at it again (and read their own code under Past
 * examinations). Admins are not candidates and are not held to it.
 */
@Service
@RequiredArgsConstructor
public class ExamSessionGuard {

    private final GroupContestRepository events;
    private final ExamAccessService access;

    /** Throws unless this session may reach this judge contest right now. */
    @Transactional(readOnly = true)
    public void requireContestAccess(Long userId, String platform, String contestId) {
        Long sessionExam = SessionMode.examId();
        Instant now = Instant.now();

        if (sessionExam != null) {
            GroupContest paper = events.findById(sessionExam).orElseThrow(() ->
                ApiException.forbidden("This examination no longer exists."));
            if (!paper.getPlatform().equalsIgnoreCase(platform)
                    || !paper.getExternalId().equals(contestId)) {
                throw ApiException.forbidden("Only this examination's contest is available "
                    + "while you are signed in for it.");
            }
            // Before the start there is nothing to read; the arena shows the countdown.
            if (paper.isOpenForParticipation(now)) access.requireUnlocked(paper, userId);
            return;
        }

        if (isAdmin()) return;
        for (GroupContest event : events.findByPlatformAndExternalId(
                platform.toUpperCase(java.util.Locale.ROOT), contestId)) {
            if (!event.isExam()) continue;
            GroupContest.Lifecycle stage = event.effectiveLifecycle(now);
            if (stage == GroupContest.Lifecycle.SCHEDULED || stage == GroupContest.Lifecycle.ACTIVE) {
                throw ApiException.forbidden("\"" + event.getName() + "\" is an examination. "
                    + "It is sat in examination mode: sign out, then sign in with your username "
                    + "and the examination password on your slip.");
            }
        }
    }

    /**
     * Holds a run from an examination session to its paper: its contest, and so its languages.
     * A run naming no contest would be the unrestricted practice runner.
     */
    @Transactional(readOnly = true)
    public void requireRunScope(String platform, String contestId) {
        Long sessionExam = SessionMode.examId();
        if (sessionExam == null) return;
        GroupContest paper = events.findById(sessionExam).orElse(null);
        if (paper == null || platform == null || contestId == null
                || !paper.getPlatform().equalsIgnoreCase(platform)
                || !paper.getExternalId().equals(contestId)) {
            throw ApiException.forbidden("While you are signed in for an examination, code runs "
                + "against that examination only.");
        }
    }

    /** Throws unless this is an examination session for this paper. */
    public void requireExamSession(Long examId) {
        if (!SessionMode.isExamSessionFor(examId)) {
            throw ApiException.forbidden("This belongs to the examination session. Sign in "
                + "with the examination password on your slip to sit the paper.");
        }
    }

    private static boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a ->
            a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }
}
