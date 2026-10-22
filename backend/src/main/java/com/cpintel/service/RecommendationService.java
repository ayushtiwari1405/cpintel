package com.cpintel.service;

import com.cpintel.analytics.Plan;
import com.cpintel.analytics.PlanCodec;
import com.cpintel.analytics.PracticePlanner;
import com.cpintel.analytics.RecommendationEngine;
import com.cpintel.analytics.RevisionQueue;
import com.cpintel.common.RecomputeLock;
import com.cpintel.entity.Recommendation;
import com.cpintel.entity.RevisionSchedule;
import com.cpintel.entity.TopicMastery;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.RecommendationRepository;
import com.cpintel.repository.jpa.RevisionScheduleRepository;
import com.cpintel.repository.jpa.TopicMasteryRepository;
import com.cpintel.roadmap.RoadmapTaxonomy;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The user's practice sheets and revision queue, as the page reads them.
 *
 * <p>Sheets were cached in Redis for 24 hours, which outlived the daily sheet itself: one built
 * at 07:00 expired at 06:00 the next morning and was served from the cache for another hour.
 * Reading the live row is one indexed query, so the cache is gone rather than patched.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationService {

    /** How long a sheet stands before "new sheet" builds another, so the button cannot be hammered. */
    static final Duration REGENERATE_COOLDOWN = Duration.ofSeconds(60);

    /** Longest a generation may hold the per-user lock. */
    private static final Duration GENERATION_LOCK = Duration.ofMinutes(2);

    /** The revision queue looks at most this far ahead for upcoming items. */
    public static final int MAX_HORIZON_DAYS = 60;

    /** The most revision items one response carries. */
    static final int MAX_REVISION_ITEMS = 50;

    private final RecommendationRepository recommendationRepository;
    private final RevisionScheduleRepository revisionScheduleRepository;
    private final TopicMasteryRepository topicMasteryRepository;
    private final RecommendationEngine recommendationEngine;
    private final RecomputeLock recomputeLock;
    private final PlanCodec codec;

    /** The three kinds of sheet, by the name used in the URL. */
    @RequiredArgsConstructor
    public enum SheetType {
        DAILY(RecommendationEngine.DAILY),
        WEEKLY(RecommendationEngine.WEEKLY),
        CONTEST_PREP(RecommendationEngine.CONTEST_PREP);

        final String recType;

        /** daily, weekly or contest-prep; anything else is a 400. */
        public static SheetType fromPath(String value) {
            if (value != null) {
                switch (value.toLowerCase(Locale.ROOT)) {
                    case "daily": return DAILY;
                    case "weekly": return WEEKLY;
                    case "contest-prep": return CONTEST_PREP;
                    default: break;
                }
            }
            throw ApiException.badRequest("Unknown plan type: use daily, weekly or contest-prep");
        }
    }

    public RecommendationPayload getDaily(Long userId) {
        return getOrGenerate(userId, SheetType.DAILY);
    }

    public RecommendationPayload getWeekly(Long userId) {
        return getOrGenerate(userId, SheetType.WEEKLY);
    }

    public RecommendationPayload getContestPrep(Long userId) {
        return getOrGenerate(userId, SheetType.CONTEST_PREP);
    }

    /**
     * Builds a new sheet in place of the live one - for a user who has finished theirs. A sheet
     * younger than {@link #REGENERATE_COOLDOWN} is returned as it is.
     */
    public RecommendationPayload regenerate(Long userId, SheetType type) {
        Optional<Recommendation> live = recommendationRepository.findLatestActiveByUserAndType(userId, type.recType);
        boolean recent = live.map(r -> r.getGeneratedAt() != null
                && r.getGeneratedAt().isAfter(Instant.now().minus(REGENERATE_COOLDOWN)))
            .orElse(false);
        if (!recent) generate(userId, type);
        return read(userId, type);
    }

    private RecommendationPayload getOrGenerate(Long userId, SheetType type) {
        if (recommendationRepository.findLatestActiveByUserAndType(userId, type.recType).isEmpty()) {
            generate(userId, type);
        }
        return read(userId, type);
    }

    /**
     * One generation per user and type at a time: two tabs opening the page together would
     * otherwise both build, and both call Codeforces for contest preparation. The loser reads
     * whatever the winner has stored by then.
     */
    private void generate(Long userId, SheetType type) {
        recomputeLock.ifFree("recommendations:" + userId + ":" + type.recType, GENERATION_LOCK, () -> {
            switch (type) {
                case DAILY -> recommendationEngine.generateDailySheet(userId);
                case WEEKLY -> recommendationEngine.generateWeeklySheet(userId);
                case CONTEST_PREP -> recommendationEngine.generateContestPrep(userId);
            }
            return Boolean.TRUE;
        });
    }

    private RecommendationPayload read(Long userId, SheetType type) {
        Optional<Recommendation> live = recommendationRepository.findLatestActiveByUserAndType(userId, type.recType);
        if (live.isEmpty()) {
            return new RecommendationPayload(type.recType, List.of(), null, null,
                Plan.Summary.of(Plan.NONE, List.of("Your plan is still being built. Try again in a moment.")),
                new Progress(0, 0));
        }
        Recommendation rec = live.get();
        List<Plan.Item> items = codec.readItems(rec.getProblemList());
        Plan.Summary summary = codec.readSummary(rec.getMetadata());
        if (summary == null) summary = Plan.Summary.of(Plan.MEASURED, List.of());
        return new RecommendationPayload(type.recType, items, rec.getGeneratedAt(), rec.getExpiresAt(),
            summary, progress(items));
    }

    private static Progress progress(List<Plan.Item> items) {
        int solved = 0, total = 0;
        for (Plan.Item item : items) {
            if (item.problems() == null) continue;
            for (Plan.Problem p : item.problems()) {
                total++;
                if (p.solved()) solved++;
            }
        }
        return new Progress(solved, total);
    }

    // -- revision ----------------------------------------------------------

    /**
     * Today's revision, the overdue skills queued behind it, and those coming due within
     * {@code horizonDays}.
     *
     * <p>Overdue means the scheduled date has passed <em>and</em> the skill is still faded: a
     * user who has practised it since has revised it, whether or not they pressed "done". Of the
     * overdue skills, {@link RevisionQueue} decides which are due today and in what order the
     * rest wait; all of them are listed. Reading the queue changes nothing - no date moves until
     * a revision is marked done.
     */
    public List<RevisionItem> getRevisionQueue(Long userId, int horizonDays) {
        if (horizonDays < 0 || horizonDays > MAX_HORIZON_DAYS) {
            throw ApiException.badRequest("horizonDays must be between 0 and " + MAX_HORIZON_DAYS);
        }

        List<RevisionSchedule> scheduled = revisionScheduleRepository.findByUserUserId(userId);
        if (scheduled.isEmpty()) {
            recommendationEngine.generateRevisionSchedule(userId);
            scheduled = revisionScheduleRepository.findByUserUserId(userId);
        }

        Map<String, TopicMastery> mastery = new HashMap<>();
        for (TopicMastery tm : topicMasteryRepository.findByUserUserIdAndScope(userId, TopicMastery.Scope.NODE)) {
            mastery.put(tm.getTopic(), tm);
        }

        Instant now = Instant.now();
        Instant horizon = now.plus(Duration.ofDays(horizonDays));
        Instant today = RevisionQueue.startOfDay(now);
        Map<String, RevisionSchedule> byTopic = new HashMap<>();
        List<RevisionQueue.Candidate> overdue = new ArrayList<>();
        List<Entry> upcoming = new ArrayList<>();
        int completedToday = 0;
        for (RevisionSchedule rs : scheduled) {
            byTopic.put(rs.getTopic(), rs);
            if (rs.getLastRevisedAt() != null && !rs.getLastRevisedAt().isBefore(today)) completedToday++;
            TopicMastery tm = mastery.get(rs.getTopic());
            double decay = tm != null && tm.getDecayScore() != null ? tm.getDecayScore()
                : rs.getDecayScore() == null ? 0 : rs.getDecayScore();
            boolean dueDate = !rs.getNextRevisionAt().isAfter(now);
            if (dueDate && decay >= PracticePlanner.REVISION_DECAY_THRESHOLD) {
                overdue.add(new RevisionQueue.Candidate(rs.getTopic(), decay, rs.getNextRevisionAt()));
            } else if (!dueDate && !rs.getNextRevisionAt().isAfter(horizon)) {
                upcoming.add(new Entry(rs, tm, decay, RevisionStatus.UPCOMING, null));
            }
        }

        int open = RevisionQueue.openSlots(completedToday);
        List<Entry> entries = new ArrayList<>();
        for (RevisionQueue.Placed p : RevisionQueue.arrange(overdue, now, open)) {
            RevisionSchedule rs = byTopic.get(p.candidate().key());
            entries.add(new Entry(rs, mastery.get(rs.getTopic()), p.candidate().decay(),
                p.status() == RevisionQueue.Status.DUE ? RevisionStatus.DUE : RevisionStatus.QUEUED,
                p.status() == RevisionQueue.Status.DUE ? null : p.position() - open));
        }
        // Every overdue skill is listed; only the look ahead is limited.
        upcoming.sort(Comparator.comparing((Entry e) -> e.rs().getNextRevisionAt())
            .thenComparing(e -> e.rs().getTopic()));
        entries.addAll(upcoming.subList(0, Math.min(upcoming.size(), MAX_REVISION_ITEMS)));

        List<String> dueKeys = entries.stream()
            .filter(e -> e.status() == RevisionStatus.DUE).map(e -> e.rs().getTopic()).toList();
        Map<String, List<Plan.Problem>> problems = recommendationEngine.revisionProblems(userId, dueKeys);

        return entries.stream()
            .map(e -> toRevisionItem(e, problems.getOrDefault(e.rs().getTopic(), List.of()), now))
            .toList();
    }

    /** @param queuePosition queued items only: its place among the queued skills, from 1 */
    private record Entry(RevisionSchedule rs, TopicMastery tm, double decay, RevisionStatus status,
                         Integer queuePosition) {}

    private static RevisionItem toRevisionItem(Entry e, List<Plan.Problem> problems, Instant now) {
        RevisionSchedule rs = e.rs();
        RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(rs.getTopic());
        Instant last = e.tm() == null ? null : e.tm().getLastPracticedAt();
        String faded = "About " + Math.round(e.decay()) + "% of this has faded"
            + (last == null ? "" : " - last practised " + Duration.between(last, now).toDays() + " days ago");
        String reason = switch (e.status()) {
            case DUE -> faded;
            case QUEUED -> faded + ". Waiting its turn: " + (e.queuePosition() == 1 ? "next in line"
                : (e.queuePosition() - 1) + " ahead of it") + ", " + RevisionQueue.SLOTS_PER_DAY
                + " come up a day";
            case UPCOMING -> "Next revision in "
                + Math.max(1, Duration.between(now, rs.getNextRevisionAt()).toDays()) + " days";
        };
        return new RevisionItem(
            rs.getRevisionId(),
            rs.getTopic(),
            def == null ? rs.getTopic() : def.displayName(),
            def == null ? null : def.track(),
            def == null ? null : def.rollupTopic(),
            rs.getNextRevisionAt(),
            rs.getRevisionPriority(),
            e.decay(),
            rs.getIntervalDays(),
            rs.getRepetitionCount(),
            rs.getEaseFactor(),
            rs.getLastRevisedAt(),
            // The workspace opens on the skill itself and offers its problems.
            def == null ? null : "/practice?node=" + def.id(),
            e.status().name(),
            reason,
            last,
            e.queuePosition(),
            problems);
    }

    /** SM-2 on success: a longer interval and an easier skill. Only the owner's items. */
    public void markRevisionDone(Long userId, Long revisionId) {
        // Looked up by owner as well as id, so somebody else's item is indistinguishable from
        // one that does not exist.
        RevisionSchedule rs = revisionScheduleRepository.findByRevisionIdAndUserUserId(revisionId, userId)
            .orElseThrow(() -> ApiException.notFound("Revision item not found"));

        double newEase     = Math.min(2.5, rs.getEaseFactor() + 0.1);
        int    newInterval = Math.max(1, (int)(rs.getIntervalDays() * newEase));

        rs.setEaseFactor(newEase);
        rs.setIntervalDays(newInterval);
        rs.setRepetitionCount(rs.getRepetitionCount() + 1);
        rs.setLastRevisedAt(Instant.now());
        rs.setNextRevisionAt(Instant.now().plusSeconds(newInterval * 86_400L));
        rs.setDecayScore(Math.max(0, rs.getDecayScore() - 15));
        revisionScheduleRepository.save(rs);
    }

    /** Today's revision, overdue but waiting its turn, and not yet due; listed in that order. */
    public enum RevisionStatus { DUE, QUEUED, UPCOMING }

    /** One skill on the revision schedule, named the way a person would recognise it. */
    public record RevisionItem(
        Long revisionId,
        String nodeKey,
        String title,
        String track,
        String topic,
        Instant nextRevisionAt,
        Integer revisionPriority,
        Double decayScore,
        Integer intervalDays,
        Integer repetitionCount,
        Double easeFactor,
        Instant lastRevisedAt,
        String practicePath,
        /** DUE, QUEUED or UPCOMING. */
        String status,
        String reason,
        Instant lastPracticedAt,
        /** QUEUED only: its place among the queued skills, from 1. */
        Integer queuePosition,
        /** Due items only: earlier solves to redo, then one new problem. */
        List<Plan.Problem> problems
    ) {}

    /** How much of a sheet is solved. */
    public record Progress(int solved, int total) {}

    public record RecommendationPayload(
        String type,
        List<Plan.Item> items,
        Instant generatedAt,
        Instant expiresAt,
        Plan.Summary summary,
        Progress progress
    ) {}
}
