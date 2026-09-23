package com.cpintel.groups;

import com.cpintel.common.Languages;
import com.cpintel.entity.GroupContest;
import com.cpintel.events.EventMapper;
import com.cpintel.events.EventService;
import com.cpintel.exception.ApiException;
import com.cpintel.practice.PracticeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which languages an event will accept, when its administrator has said.
 *
 * <p>An examination may be set to "C++ and Python only", and that has to mean the same thing in
 * both places a candidate meets a language: the picker they choose from, and the submission
 * they send. Those are answered by different code — one reads the judge's catalogue, the other
 * posts to it — so the rule lives here and both ask it, rather than each implementing it.
 *
 * <p><b>It sits in the service layer, beside {@link ProctoringGate}, for the same reason.</b>
 * The restriction is a property of the <em>event</em> — an admin decided it — and has nothing
 * to do with which judge the event runs on. Inside a provider it would have to be written
 * twice, and the copy in the Codeforces provider would drift from the one in the DOMjudge
 * provider until an examination was restricted on one judge and open on the other.
 *
 * <p><b>The common case is to do nothing.</b> Practice, every ordinary contest, and any
 * examination whose admin did not restrict anything all pass through untouched, and the
 * do-nothing path costs one lookup that is already cached. Restricting languages is the
 * exception, and this is written so that it reads like one.
 *
 * <p><b>Unrecognised languages are refused while a restriction is in force.</b> See
 * {@link Languages} — an admin who named three languages meant three, and showing a fourth
 * because a judge called it something this system has not seen before would quietly overrule
 * them. It fails closed, visibly, and clearing the restriction is the way back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LanguagePolicy {

    private final GroupService groups;
    private final EventService events;

    /**
     * The languages this event is restricted to, or empty for "whatever the judge allows".
     *
     * <p>Empty is the answer for a contest nobody is sitting through CPIntel, for an event with
     * no list set, and for a list that survived into the database saying nothing usable. All
     * three mean the same thing to every caller — no restriction — which is what keeps the
     * unrestricted path free of special cases.
     */
    public Set<String> restrictionFor(Long userId, String platform, String externalId) {
        GroupsDto.ContestSummary contest;
        try {
            contest = groups.activeFor(userId, platform, externalId);
        } catch (ApiException e) {
            // A platform this system does not recognise names no event, so it restricts
            // nothing. Swallowed rather than propagated because one caller — the local
            // runner — takes these two values as an untrusted hint from the page, and a
            // malformed hint must degrade to "no event" rather than refuse to compile code
            // that Practice would have compiled without them. It is not a way around the
            // rule: omitting the hint entirely already means the same thing.
            log.debug("Ignoring an unusable event reference {}/{}: {}",
                platform, externalId, e.getMessage());
            return Set.of();
        }
        if (contest == null) return Set.of();

        GroupContest event = events.require(contest.contestId());
        return restrictionFor(event);
    }

    /** The same question asked of an event already in hand. */
    public Set<String> restrictionFor(GroupContest event) {
        List<String> configured = EventMapper.languagesOf(event);
        if (configured.isEmpty()) return Set.of();

        Set<String> allowed = new LinkedHashSet<>();
        for (String id : configured) {
            String known = id == null ? null : id.trim().toLowerCase(java.util.Locale.ROOT);
            if (Languages.isKnown(known)) allowed.add(known);
        }

        if (allowed.isEmpty()) {
            // A stored list that classifies to nothing. Treated as no restriction rather than
            // as "nothing is allowed", because the second reading locks a whole room out of a
            // paper over a data problem nobody can see from the candidate's side.
            log.warn("Event {} has an allowed-language list that names nothing recognised ({}); "
                + "treating it as unrestricted", event.getContestId(), configured);
            return Set.of();
        }
        return allowed;
    }

    /**
     * The judge's languages, narrowed to what this event allows.
     *
     * <p>Returned in the judge's own order, because that is the order the contestant has seen
     * on the judge's own pages, and re-sorting it here would be a gratuitous difference.
     */
    public List<PracticeDto.LanguageOption> filter(List<PracticeDto.LanguageOption> offered,
                                                   Set<String> allowed) {
        if (allowed.isEmpty()) return offered;
        return offered.stream()
            .filter(option -> {
                String canonical = Languages.classify(option.id(), option.label());
                return canonical != null && allowed.contains(canonical);
            })
            .toList();
    }

    /**
     * Refuses a submission in a language this event does not accept.
     *
     * <p>Checked against the <em>filtered</em> list rather than re-derived, so the gate and the
     * picker cannot disagree — a language the contestant was offered is by construction one
     * they may send, and there is no second rule to keep in step with the first.
     *
     * <p>Enforced on the server because hiding an option stops an honest mistake and nothing
     * else: the submit endpoint is a plain authenticated POST, and a candidate who wants to
     * send Python to a C++-only paper is exactly the person who will not be stopped by a
     * shortened dropdown.
     */
    public void requireAllowed(Set<String> allowed, String languageId,
                               List<PracticeDto.LanguageOption> permitted) {
        if (allowed.isEmpty()) return;

        boolean ok = permitted.stream()
            .anyMatch(option -> option.id() != null && option.id().equals(languageId));
        if (ok) return;

        String names = allowed.stream().map(Languages::labelFor).sorted()
            .reduce((a, b) -> a + ", " + b).orElse("");

        log.info("Refused a submission in language {}: this event allows {}", languageId, names);

        throw ApiException.badRequest(
            "This examination only accepts " + names + ". Pick one of those in the editor and "
            + "submit again.");
    }
}
