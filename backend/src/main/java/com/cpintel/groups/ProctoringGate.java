package com.cpintel.groups;

import com.cpintel.entity.GroupContest;
import com.cpintel.events.ExamAccessService;
import com.cpintel.events.EventService;
import com.cpintel.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Refuses a submission into an examination that is not being sat the way it was set.
 *
 * <p><b>Examinations only.</b> An ordinary contest passes straight through, unconditionally.
 * That is not a configuration default but the product's shape: monitoring is what makes an
 * examination an examination, and a contest is practice among people who chose to enter it.
 * {@code EventService.lockdownFor} refuses to mark a contest as monitored at all, so the check
 * below is the second half of one rule rather than a second rule that could drift from it.
 *
 * <p><b>Two things are checked, and they fail differently.</b>
 *
 * <ul>
 *   <li><b>The paper was unlocked</b> — this candidate typed the passwords handed out in the
 *       room. Without it, the whole of "sitting the examination" would be "be on the roster",
 *       and somebody at home could submit into a paper being invigilated three miles away.</li>
 *   <li><b>Something is monitoring right now</b> — a heartbeat has arrived recently. Hiding the
 *       submit button when the monitor is closed stops an honest mistake and nothing else; the
 *       endpoint is a plain authenticated POST, and somebody who closed the monitor in order
 *       to open something else is exactly the person a disabled button will not stop.</li>
 * </ul>
 *
 * <p><b>Neither is proof.</b> A heartbeat is a claim by a client and a password can be read
 * down a phone. Both raise the cost of sitting a paper unsupervised from "close a window" to
 * something that needs a confederate and a forged request, and the session log remains the
 * thing an invigilator actually reads. Nothing here produces a verdict about anybody.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProctoringGate {

    private final GroupService groups;
    private final EventService events;
    private final ContestMonitorRegistry monitors;
    private final ExamAccessService access;

    /**
     * Throws if this candidate may not submit into this event right now.
     *
     * @param platform   the judge, as the compete arena names it
     * @param externalId the judge's own contest id
     */
    public void requireMonitored(Long userId, String platform, String externalId) {
        GroupsDto.ContestSummary contest = groups.activeFor(userId, platform, externalId);

        // Not an event CPIntel runs at all: nothing was ever promised about watching it.
        if (contest == null) return;
        // A contest. See the class note — this is the one line that keeps practice unproctored.
        if (!GroupContest.Kind.EXAM.name().equals(contest.kind())) return;

        GroupContest exam = events.require(contest.contestId());

        // The paper has to have been opened with the passwords issued in the room. Checked
        // before monitoring, because a candidate who never unlocked it has a different problem
        // from one whose monitor stopped, and telling them about the monitor would send them
        // looking for the wrong fix.
        access.requireUnlocked(exam, userId);

        if (!contest.lockdownRequired()) return;
        if (monitors.isMonitored(userId, contest.contestId())) return;

        log.info("Refused a submission from user {} into monitored exam {} ({}/{}): "
            + "no recent heartbeat", userId, contest.contestId(), platform, externalId);

        // Named precisely enough to act on. "Forbidden" on its own would read as a permissions
        // problem, and the candidate would go looking for an invigilator instead of reopening
        // the window sitting behind their browser.
        throw ApiException.forbidden(
            "This examination is monitored, and nothing is currently reporting from your "
                + "machine. Return to the examination window and wait a few seconds, then "
                + "submit again. If you are using the desktop app, make sure it is still "
                + "running.");
    }
}
