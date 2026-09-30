package com.cpintel.events;

import com.cpintel.compete.CompeteDto;
import com.cpintel.compete.DomjudgeContestCache;
import com.cpintel.entity.GroupContest;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.domjudge.DjModels;
import com.cpintel.integration.domjudge.DomjudgeClient;
import com.cpintel.integration.domjudge.DomjudgeJudges;
import com.cpintel.integration.domjudge.JudgeContestRef;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The problems of the judge contest an event is laid over, as the judge lists them.
 *
 * <p>What the admin's Problems tab starts from: the labels and names come from DOMjudge, so the
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

    public List<EventsDto.JudgeProblem> problems(Long eventId) {
        GroupContest event = events.require(eventId);
        if (!CompeteDto.Platform.DOMJUDGE.name().equals(event.getPlatform())) {
            throw ApiException.badRequest(
                "Only a DOMjudge contest can list its problems here; enter them by hand.");
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
