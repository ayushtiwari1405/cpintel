package com.cpintel.events;

import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.GroupContest;
import com.cpintel.repository.jpa.GroupContestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Writes the parts of an examination log that CPIntel knows first-hand.
 *
 * <p>Most of the log comes from the candidate's own client, which is the only thing that can
 * see a window losing focus. A submission is different: it passes through the server on its way
 * to the judge, so the server can record it without being told — and a row written here cannot
 * be withheld by somebody who would rather it were not there.
 *
 * <p>Does nothing at all for a contest, or for a round no examination was laid over, which is
 * the common case. That path is written to be the one with no conditions attached to it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExamSessionRecorder {

    private final GroupContestRepository eventRepository;
    private final ExamEventService examEvents;

    /** Records that this candidate sent a solution, if they were sitting an examination. */
    public void recordSubmission(Long userId, String platform, String externalContestId,
                                 String problemLabel, String submissionId) {
        GroupContest exam = activeExam(userId, platform, externalContestId);
        if (exam == null) return;

        examEvents.recordServerSide(exam, userId, ExamEvent.Type.PROBLEM_SUBMITTED, problemLabel,
            submissionId == null ? null : "Submission " + submissionId);
    }

    /**
     * The examination this person is sitting on this external contest, if any.
     *
     * Only one that is open: a submission arriving after the window has closed is the judge's
     * business to refuse, and recording it as part of the session would put an event outside
     * the examination it claims to belong to.
     */
    private GroupContest activeExam(Long userId, String platform, String externalContestId) {
        try {
            Instant now = Instant.now();
            List<GroupContest> candidates =
                eventRepository.findForParticipant(userId, platform, externalContestId);
            return candidates.stream()
                .filter(GroupContest::isExam)
                .filter(event -> event.isOpenForParticipation(now))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            // The log is worth having; it is not worth failing a submission over.
            log.warn("Could not resolve the examination for user {} on {}/{}: {}",
                userId, platform, externalContestId, e.getMessage());
            return null;
        }
    }
}
