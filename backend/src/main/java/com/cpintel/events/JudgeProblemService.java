package com.cpintel.events;

import com.cpintel.compete.CfContestScraper;
import com.cpintel.compete.CompeteDto;
import com.cpintel.compete.DomjudgeContestCache;
import com.cpintel.entity.GroupContest;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.domjudge.DjModels;
import com.cpintel.integration.domjudge.DomjudgeClient;
import com.cpintel.integration.domjudge.DomjudgeJudges;
import com.cpintel.integration.domjudge.JudgeContestRef;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import com.cpintel.practice.CfSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The problems of the judge contest an event is laid over, as the judge lists them.
 *
 * <p>What the admin's Problems tab starts from: the labels and names come from the judge, so the
 * only thing left to type is what each one is worth. The labels are the ones a submission is
 * recorded under, which is what lets the leaderboard match a solve to its marks.
 *
 * <p>An admin has no DOMjudge login of their own here. The read goes through the service
 * account when there is one, and otherwise as the first assigned candidate with an attached
 * login — the problem list is the same for every team in a contest, so whose eyes it is read
 * through does not change the answer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JudgeProblemService {

    private final EventService events;
    private final DomjudgeJudges judges;
    private final DomjudgeContestCache cache;
    private final DomjudgeCredentialStore credentials;
    private final CfSessionStore cfSessions;
    private final CfContestScraper cfScraper;
    private final com.cpintel.compete.CfManagerApi cfManager;

    public List<EventsDto.JudgeProblem> problems(Long eventId) {
        GroupContest event = events.require(eventId);
        if (CompeteDto.Platform.CODEFORCES.name().equals(event.getPlatform())) {
            return codeforcesProblems(event);
        }
        if (!CompeteDto.Platform.DOMJUDGE.name().equals(event.getPlatform())) {
            throw ApiException.badRequest("This judge cannot list its problems; enter them by hand.");
        }
        DomjudgeClient domjudge = judges.forClassroom(event.getClassroomId());
        if (!domjudge.isConfigured()) {
            throw ApiException.badRequest("This event's classroom has no DOMjudge URL set.");
        }
        String judgeId = JudgeContestRef.judgeIdOf(event.getExternalId());

        DomjudgeCredentialStore.Stored as = domjudge.hasServiceAccount() ? null
            : readAs(event.getClassroomId(), eventId);
        List<DjModels.ContestProblem> listed;
        try {
            listed = cache.problems(as, event.getExternalId());
        } catch (Exception e) {
            log.info("Could not list the problems of DOMjudge contest {} for event {}: {}",
                event.getExternalId(), eventId, e.getMessage());
            throw ApiException.badRequest("DOMjudge did not return the problems of contest "
                + judgeId + ". Check the contest id, and that it has problems.");
        }

        return listed.stream()
            .filter(p -> p.getLabel() != null && !p.getLabel().isBlank())
            .map(p -> new EventsDto.JudgeProblem(p.getLabel(), p.getName(), p.getId()))
            .toList();
    }

    /**
     * Read through the first assigned candidate whose connected Codeforces session shows the
     * problems. Codeforces lists them only once the contest is open to that account.
     */
    private List<EventsDto.JudgeProblem> codeforcesProblems(GroupContest event) {
        com.cpintel.integration.codeforces.CfContestRef ref =
            com.cpintel.integration.codeforces.CfContestRef.parse(event.getExternalId());
        int contestId = ref.id();
        // A group contest's manager sees its problems at any time, through the classroom's key.
        if (ref.inGroup()) {
            try {
                var standings = cfManager.standings(ref);
                if (standings != null && standings.getProblems() != null && !standings.getProblems().isEmpty()) {
                    return standings.getProblems().stream()
                        .filter(p -> p.getIndex() != null && !p.getIndex().isBlank())
                        .map(p -> new EventsDto.JudgeProblem(p.getIndex(), p.getName(), p.getIndex()))
                        .toList();
                }
            } catch (ApiException e) {
                throw ApiException.badRequest("Codeforces did not list the problems with the "
                    + "classroom's key. " + e.getMessage());
            } catch (Exception e) {
                log.info("Manager read of contest {} failed: {}", ref, e.getMessage());
            }
        }
        boolean anySession = false;
        for (Long userId : events.participantIds(event.getContestId())) {
            CfSessionStore.StoredSession session = cfSessions.find(userId);
            if (session == null) continue;
            anySession = true;
            List<CompeteDto.ContestProblem> listed;
            try {
                listed = cfScraper.dashboard(session.cookieHeader(), ref).problems();
            } catch (Exception e) {
                log.info("Could not read Codeforces contest {} for event {}: {}",
                    contestId, event.getContestId(), e.getMessage());
                continue;
            }
            if (listed.isEmpty()) continue;
            return listed.stream()
                .filter(p -> p.index() != null && !p.index().isBlank())
                .map(p -> new EventsDto.JudgeProblem(p.index(), p.name(), p.index()))
                .toList();
        }
        throw ApiException.badRequest(anySession
            ? "Codeforces showed no problems for contest " + contestId + ". It lists them once "
                + "the contest has started for a candidate."
            : "Nobody assigned has connected Codeforces, so the contest cannot be read.");
    }

    /** Somebody on the roster whose attached login can read the contest. */
    private DomjudgeCredentialStore.Stored readAs(Long classroomId, Long eventId) {
        for (Long userId : events.participantIds(eventId)) {
            DomjudgeCredentialStore.Stored stored = credentials.find(classroomId, userId);
            if (stored != null) return stored;
        }
        throw ApiException.badRequest("There is no DOMjudge service account, and nobody assigned "
            + "to this event has a DOMjudge login attached, so there is nothing to read the "
            + "contest as. Assign candidates with attached logins first, or enter the problems "
            + "by hand.");
    }
}
