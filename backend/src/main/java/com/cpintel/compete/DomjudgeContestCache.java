package com.cpintel.compete;

import com.cpintel.integration.domjudge.DjModels;
import com.cpintel.integration.domjudge.DomjudgeClient;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One read of the judge, fanned out to every contestant who is allowed to share it.
 *
 * <p>This class exists because of a specific, measured failure on the Codeforces side of the
 * arena: every contestant's page polled for its own verdicts, so 200 people cost 200 calls
 * every few seconds. Against a public judge that meant permanent rate-limit saturation. Against
 * a self-hosted DOMjudge there is no rate limiter to hit — which makes it worse, not better,
 * because the thing being flooded is the same machine that is compiling and running everyone's
 * submissions. A judge that is busy answering the scoreboard is a judge that is slow to judge.
 *
 * <p>So the arena never calls DOMjudge per user where it can avoid it. It calls this, which
 * holds one copy of each resource and refreshes it on a timer sized to how fast that resource
 * actually changes.
 *
 * <p><b>Every key is scoped to the credentials that filled it, and that is not optional.</b>
 * When the deployment has a service account, every contestant shares one entry and the fan-out
 * is total — 200 contestants cost one fetch. When it does not, reads are made as each
 * contestant, and two contestants' answers are <em>not interchangeable</em>: DOMjudge filters
 * a team account's view of {@code /submissions} to its own team, so a cache keyed on the
 * contest alone would hand one team's submissions to another. That is a correctness bug and a
 * disclosure bug at once, and it would surface as contestants seeing each other's verdicts
 * during a live round. Scoping costs the fan-out exactly when there is no service account to
 * provide it, which is the honest trade rather than a silent one.
 *
 * <p><b>Single-flight and stale-serving.</b> A plain "refresh when expired" cache turns 200
 * simultaneous readers into 200 simultaneous fetches the instant the entry expires — the
 * stampede it was supposed to prevent, moved to a different moment. So exactly one thread
 * refreshes an expired entry while every other reader is handed the slightly stale copy and
 * returns immediately. Only a completely cold entry makes readers wait, and then they all wait
 * on the same single fetch. During a contest the practical effect is that a verdict is at most
 * one live-TTL window old and no reader ever queues behind the judge.
 */
@Component
@Slf4j
public class DomjudgeContestCache {

    /** Submissions, judgements and contest state — the things that move during a round. */
    static final Duration LIVE_TTL = Duration.ofSeconds(4);

    /** Problems, languages, teams, judgement types — fixed once the contest is built. */
    static final Duration STATIC_TTL = Duration.ofMinutes(5);

    private final DomjudgeClient domjudge;
    private final Duration liveTtl;
    private final Duration staticTtl;

    private final Map<String, Slot<?>> slots = new ConcurrentHashMap<>();

    /**
     * Annotated because there are two constructors and Spring will not guess between them: it
     * looks for a no-arg one instead, finds none, and refuses to start the whole application
     * on "No default constructor found". The second constructor below is test-only, so this is
     * the one the container wants.
     */
    @Autowired
    public DomjudgeContestCache(DomjudgeClient domjudge) {
        this(domjudge, LIVE_TTL, STATIC_TTL);
    }

    /**
     * Constructor with explicit lifetimes.
     *
     * Exists so the staleness behaviour can be tested without a test that sleeps for the real
     * TTL — the interesting cases are all about what happens at the moment an entry expires,
     * and waiting four seconds per assertion to reach that moment is not a test anyone runs.
     */
    DomjudgeContestCache(DomjudgeClient domjudge, Duration liveTtl, Duration staticTtl) {
        this.domjudge = domjudge;
        this.liveTtl = liveTtl;
        this.staticTtl = staticTtl;
    }

    /**
     * Which identity a cached entry was filled by.
     *
     * Null means the deployment's own service account, whose answers every contestant may
     * share. Anything else is one contestant, and its entries are theirs alone. The username
     * rather than the CPIntel user id, because two CPIntel accounts pointed at the same
     * DOMjudge login genuinely do see the same thing and may share.
     */
    private String scope(DomjudgeCredentialStore.Stored as) {
        return as == null ? "svc" : "u/" + as.username();
    }

    /** Keys end in the contest id so {@link #evict} can still find every entry for one contest. */
    private String key(String name, DomjudgeCredentialStore.Stored as, String contestId) {
        return name + ":" + scope(as) + ":" + contestId;
    }

    // ------------------------------------------------------------------ reads

    public DjModels.Contest contest(DomjudgeCredentialStore.Stored as, String contestId) {
        return get(key("contest", as, contestId), staticTtl,
            () -> domjudge.getContest(as, contestId));
    }

    public DjModels.State state(DomjudgeCredentialStore.Stored as, String contestId) {
        return get(key("state", as, contestId), liveTtl, () -> domjudge.getState(as, contestId));
    }

    public List<DjModels.ContestProblem> problems(DomjudgeCredentialStore.Stored as,
                                                  String contestId) {
        List<DjModels.ContestProblem> problems = get(key("problems", as, contestId), staticTtl,
            () -> domjudge.getProblems(as, contestId));
        if (problems == null) return List.of();

        List<DjModels.ContestProblem> ordered = new ArrayList<>(problems);
        ordered.sort(Comparator.comparing(
            p -> p.getLabel() == null ? "" : p.getLabel()));
        return ordered;
    }

    public List<DjModels.Language> languages(DomjudgeCredentialStore.Stored as, String contestId) {
        List<DjModels.Language> languages = get(key("languages", as, contestId), staticTtl,
            () -> domjudge.getLanguages(as, contestId));
        return languages == null ? List.of() : languages;
    }

    public List<DjModels.Team> teams(DomjudgeCredentialStore.Stored as, String contestId) {
        List<DjModels.Team> teams = get(key("teams", as, contestId), staticTtl,
            () -> domjudge.getTeams(as, contestId));
        return teams == null ? List.of() : teams;
    }

    /** Verdict code to its meaning, e.g. {@code AC -> solved}. */
    public Map<String, DjModels.JudgementType> judgementTypes(DomjudgeCredentialStore.Stored as,
                                                              String contestId) {
        List<DjModels.JudgementType> types = get(key("jtypes", as, contestId), staticTtl,
            () -> domjudge.getJudgementTypes(as, contestId));

        Map<String, DjModels.JudgementType> byId = new HashMap<>();
        if (types != null) {
            for (DjModels.JudgementType type : types) {
                if (type.getId() != null) byId.put(type.getId(), type);
            }
        }
        return byId;
    }

    /**
     * The judge's own scoreboard, shared by every contestant asking for their rank.
     *
     * One fetch serves the whole room, which is the only reason a live rank is affordable at
     * all — the Codeforces equivalent has to scrape a page per contestant. Unlike submissions,
     * a scoreboard is the same document for everyone who can read it, so the only reason this
     * is scoped at all is that a team account may be served the frozen view where the service
     * account is served the live one.
     */
    public DjModels.Scoreboard scoreboard(DomjudgeCredentialStore.Stored as, String contestId) {
        return get(key("scoreboard", as, contestId), liveTtl,
            () -> domjudge.getScoreboard(as, contestId));
    }

    public List<DjModels.Submission> submissions(DomjudgeCredentialStore.Stored as,
                                                 String contestId) {
        List<DjModels.Submission> subs = get(key("subs", as, contestId), liveTtl,
            () -> domjudge.getSubmissions(as, contestId));
        return subs == null ? List.of() : subs;
    }

    /**
     * The current verdict for each submission, keyed by submission id.
     *
     * Superseded judgements are dropped: a rejudge leaves the old row in place with
     * {@code valid=false}, and showing a contestant the verdict their code used to get would be
     * worse than showing none. Where several valid judgements exist the last one wins.
     */
    public Map<String, DjModels.Judgement> judgementsBySubmission(
            DomjudgeCredentialStore.Stored as, String contestId) {
        List<DjModels.Judgement> judgements = get(key("judgements", as, contestId), liveTtl,
            () -> domjudge.getJudgements(as, contestId));

        Map<String, DjModels.Judgement> current = new HashMap<>();
        if (judgements != null) {
            for (DjModels.Judgement judgement : judgements) {
                if (judgement.getSubmission_id() == null) continue;
                if (Boolean.FALSE.equals(judgement.getValid())) continue;
                current.put(judgement.getSubmission_id(), judgement);
            }
        }
        return current;
    }

    /**
     * Resolves a team name to its DOMjudge id.
     *
     * Matched case- and whitespace-insensitively against both {@code name} and
     * {@code display_name}, exactly as the standings provider does — an admin who typed a
     * member's team name into one feature should not find it works there and not here.
     */
    public String teamIdByName(DomjudgeCredentialStore.Stored as, String contestId,
                               String teamName) {
        if (teamName == null || teamName.isBlank()) return null;
        String wanted = normalise(teamName);

        for (DjModels.Team team : teams(as, contestId)) {
            if (team.getId() == null) continue;
            if (wanted.equals(normalise(team.getName()))
                || wanted.equals(normalise(team.getDisplay_name()))) {
                return team.getId();
            }
        }
        return null;
    }

    /**
     * Drops everything held for one contest, across every identity that cached it.
     *
     * Contest-wide on purpose. The caller evicting is usually a contestant who has just
     * submitted, and the entry their own next read will hit is only one of several — leaving
     * the others in place would make a submission appear for its author several seconds before
     * anybody else, including on a shared service-account entry.
     */
    public void evict(String contestId) {
        slots.keySet().removeIf(key -> key.endsWith(":" + contestId));
    }

    private String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT)
            .replaceAll("\\s+", " ");
    }

    // ------------------------------------------------------------- the cache

    /**
     * One cached value, its age, and the lock that serialises refreshes of it.
     *
     * {@code value} is deliberately readable without holding the lock — a reader that finds a
     * refresh already in flight takes the stale value and leaves rather than queueing.
     */
    private static final class Slot<T> {
        volatile T value;
        volatile Instant loadedAt;
        final ReentrantLock lock = new ReentrantLock();
    }

    @SuppressWarnings("unchecked")
    private <T> T get(String key, Duration ttl, Supplier<T> loader) {
        Slot<T> slot = (Slot<T>) slots.computeIfAbsent(key, k -> new Slot<>());

        T current = slot.value;
        Instant loadedAt = slot.loadedAt;
        boolean fresh = current != null && loadedAt != null
            && Instant.now().isBefore(loadedAt.plus(ttl));
        if (fresh) return current;

        if (current == null) {
            // Cold: every caller waits on the one load, then they all see the result. The
            // second and later waiters re-check under the lock so they do not load again.
            slot.lock.lock();
            try {
                if (slot.value != null) return slot.value;
                return load(slot, key, loader);
            } finally {
                slot.lock.unlock();
            }
        }

        // Warm but stale: one thread refreshes, everyone else keeps reading the old copy.
        if (slot.lock.tryLock()) {
            try {
                Instant since = slot.loadedAt;
                if (since != null && Instant.now().isBefore(since.plus(ttl))) {
                    return slot.value;   // somebody refreshed while we were deciding to
                }
                T refreshed = load(slot, key, loader);
                return refreshed == null ? current : refreshed;
            } finally {
                slot.lock.unlock();
            }
        }
        return current;
    }

    private <T> T load(Slot<T> slot, String key, Supplier<T> loader) {
        try {
            T loaded = loader.get();
            slot.value = loaded;
            slot.loadedAt = Instant.now();
            return loaded;
        } catch (Exception e) {
            // A judge that blinks must not empty the arena. The stale copy stays in place and
            // its age is what the page shows; only a cold read surfaces the failure.
            log.warn("DOMjudge read failed for {}: {}", key, e.getMessage());
            if (slot.value == null) throw e;
            // Back off from hammering a judge that is down, without discarding what we have.
            slot.loadedAt = Instant.now();
            return slot.value;
        }
    }
}
