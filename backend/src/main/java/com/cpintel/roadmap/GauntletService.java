package com.cpintel.roadmap;

import com.cpintel.entity.PlacementResult;
import com.cpintel.entity.RoadmapNode;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.PlacementResultRepository;
import com.cpintel.repository.jpa.RoadmapNodeRepository;
import com.cpintel.service.RoadmapService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Serves the placement gauntlet, marks it, and maps the result onto the roadmap.
 *
 * <p>Marking happens here and only here. The page climbs each area's tiers by asking
 * {@link #check} after every pair, but the placement itself is recomputed from the full set of
 * answers on {@link #submit}, so the roadmap never moves on the client's say-so.
 *
 * <p><b>Placement only ever moves a node forwards.</b> A node whose band ends at or below the
 * area's placed rating is marked done; one whose band starts within a tier above it is
 * unlocked. Nothing already done is undone, so retaking the gauntlet on a bad day cannot cost
 * anybody progress they had earned by solving problems.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GauntletService {

    /** How far above the placed rating the next skills open up. */
    private static final int FRONTIER = 200;

    private final RoadmapService roadmapService;
    private final RoadmapNodeRepository nodeRepository;
    private final PlacementResultRepository results;
    private final GauntletAttempts attempts;
    private final ObjectMapper json;

    /**
     * Days between completed attempts.
     *
     * <p>Every tier's right answers are shown once it is checked — the gauntlet teaches as it
     * places — so an immediate retake would be a test of memory, not of level. A week is long
     * enough that a retake reflects practice, and short enough that nobody is stuck at a
     * placement they have outgrown.
     */
    @Value("${cpintel.gauntlet.retake-days:7}")
    private int retakeDays = 7;

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public GauntletDto.Paper paper(Long userId) {
        List<GauntletDto.SectionView> sections = new ArrayList<>();
        for (GauntletBank.Section section : GauntletBank.SECTIONS) {
            List<GauntletDto.QuestionView> questions = GauntletBank.QUESTIONS.stream()
                .filter(q -> q.section().equals(section.id()))
                .map(q -> new GauntletDto.QuestionView(q.id(), q.tier(), q.prompt(), q.code(),
                    shuffled(q)))
                .toList();
            sections.add(new GauntletDto.SectionView(section.id(), section.title(),
                section.blurb(), questions));
        }
        List<Integer> tiers = new ArrayList<>();
        for (int rating : GauntletBank.TIER_RATING) tiers.add(rating);
        return new GauntletDto.Paper(sections, tiers, lastResult(userId), nextAttemptAt(userId));
    }

    /** When this user may start another attempt, or null when they may now. */
    public Instant nextAttemptAt(Long userId) {
        return results.findFirstByUserIdOrderByCreatedAtDesc(userId)
            .map(last -> last.getCreatedAt().plus(java.time.Duration.ofDays(retakeDays)))
            .filter(next -> next.isAfter(Instant.now()))
            .orElse(null);
    }

    @Transactional(readOnly = true)
    public GauntletDto.ResultView lastResult(Long userId) {
        return results.findFirstByUserIdOrderByCreatedAtDesc(userId).map(this::toView).orElse(null);
    }

    /** Opens an attempt. Answers are recorded against it as they are checked. */
    public GauntletDto.Started start(Long userId) {
        Instant next = nextAttemptAt(userId);
        if (next != null) {
            throw ApiException.badRequest("You can retake the gauntlet from "
                + java.time.format.DateTimeFormatter.ofPattern("d MMMM")
                    .withZone(java.time.ZoneOffset.UTC).format(next)
                + ". The answers were shown last time, so a retake straight away would measure "
                + "memory rather than level.");
        }
        String id = java.util.UUID.randomUUID().toString();
        attempts.save(id, new GauntletAttempts.Attempt(userId, new HashMap<>()));
        return new GauntletDto.Started(id);
    }

    /**
     * Marks one tier's answers, records them in the attempt, and says what the right ones were.
     *
     * <p>Three rules keep this from being an answer key. A question can be checked once per
     * attempt, and that first answer is the one that counts. A tier can be checked only after
     * the tier below it in the same area was passed in this attempt, which is exactly the
     * climb the page makes. And the placement is computed from what was recorded here, not
     * from anything sent at the end.
     */
    public List<GauntletDto.Checked> check(Long userId, String attemptId,
                                           Map<String, Integer> answers) {
        GauntletAttempts.Attempt attempt = requireAttempt(userId, attemptId);
        Map<String, Integer> recorded = new HashMap<>(attempt.answers());

        List<GauntletDto.Checked> out = new ArrayList<>();
        answers.forEach((id, picked) -> {
            GauntletBank.Question q = GauntletBank.BY_ID.get(id);
            if (q == null) throw ApiException.badRequest("Unknown question " + id);
            if (recorded.containsKey(id)) {
                throw ApiException.badRequest("That question has already been answered.");
            }
            if (q.tier() > 1 && !tierPassed(q.section(), q.tier() - 1, recorded)) {
                throw ApiException.badRequest(
                    "Tier " + q.tier() + " opens once tier " + (q.tier() - 1) + " is passed.");
            }
        });
        answers.forEach((id, picked) -> {
            GauntletBank.Question q = GauntletBank.BY_ID.get(id);
            recorded.put(id, picked == null ? -1 : picked);
            out.add(new GauntletDto.Checked(id, isCorrect(q, picked), correctIndex(q),
                q.explanation()));
        });

        attempts.save(attemptId, new GauntletAttempts.Attempt(userId, recorded));
        return out;
    }

    private GauntletAttempts.Attempt requireAttempt(Long userId, String attemptId) {
        GauntletAttempts.Attempt attempt = attempts.find(attemptId)
            .orElseThrow(() -> ApiException.badRequest(
                "This attempt has expired. Start the gauntlet again."));
        if (!attempt.userId().equals(userId)) {
            throw ApiException.forbidden("Not your attempt.");
        }
        return attempt;
    }

    private static boolean tierPassed(String section, int tier, Map<String, Integer> recorded) {
        List<GauntletBank.Question> inTier = GauntletBank.QUESTIONS.stream()
            .filter(q -> q.section().equals(section) && q.tier() == tier)
            .toList();
        return !inTier.isEmpty() && inTier.stream()
            .allMatch(q -> isCorrect(q, recorded.get(q.id())));
    }

    // ----------------------------------------------------------------- submit

    /**
     * Places the user from their answers and moves their roadmap to match.
     *
     * <p>Every area is recomputed here from the answers alone: climb the tiers in order, and
     * stop at the first one without both questions right. An unanswered question is a wrong
     * one, which is what a climb that stopped early leaves behind.
     */
    @Transactional
    public GauntletDto.ResultView submit(Long userId, String attemptId) {
        Map<String, Integer> answers = requireAttempt(userId, attemptId).answers();
        attempts.delete(attemptId);

        List<GauntletDto.SectionResult> sections = new ArrayList<>();
        Map<String, Integer> ratingByTrack = new HashMap<>();

        for (GauntletBank.Section section : GauntletBank.SECTIONS) {
            int passed = 0;
            for (int tier = 1; tier <= GauntletBank.TIER_RATING.length; tier++) {
                if (!tierPassed(section.id(), tier, answers)) break;
                passed = tier;
            }
            int rating = passed == 0
                ? GauntletBank.FLOOR_RATING : GauntletBank.TIER_RATING[passed - 1];
            sections.add(new GauntletDto.SectionResult(section.id(), section.title(), passed,
                rating));
            for (String track : section.tracks()) ratingByTrack.put(track, rating);
        }

        int overall = (int) Math.round(sections.stream()
            .mapToInt(GauntletDto.SectionResult::rating).average().orElse(0) / 100.0) * 100;

        int placed = applyToRoadmap(userId, ratingByTrack, overall);

        PlacementResult saved;
        try {
            saved = results.save(PlacementResult.builder()
                .userId(userId)
                .overallRating(overall)
                .sections(json.writeValueAsString(sections))
                .nodesPlaced(placed)
                .createdAt(Instant.now())
                .build());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not store a placement result", e);
        }
        log.info("Placed user {} at ~{} ({} roadmap skills marked done)", userId, overall, placed);
        return toView(saved);
    }

    /** Returns how many nodes were newly marked done. */
    private int applyToRoadmap(Long userId, Map<String, Integer> ratingByTrack, int overall) {
        roadmapService.ensureSeeded(userId);
        Instant now = Instant.now();
        int placed = 0;

        List<RoadmapNode> nodes = nodeRepository.findByUserUserIdOrderByOrderIndex(userId);
        for (RoadmapNode node : nodes) {
            RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(node.getNodeKey());
            if (def == null) continue;
            int rating = ratingByTrack.getOrDefault(def.track(), overall);

            if (rating > GauntletBank.FLOOR_RATING && def.maxRating() <= rating) {
                if (!"COMPLETED".equals(node.getStatus())) {
                    node.setStatus("COMPLETED");
                    if (node.getCompletedAt() == null) node.setCompletedAt(now);
                    if (node.getUnlockedAt() == null) node.setUnlockedAt(now);
                    placed++;
                }
            } else if (def.minRating() <= rating + FRONTIER && "LOCKED".equals(node.getStatus())) {
                node.setStatus("UNLOCKED");
                if (node.getUnlockedAt() == null) node.setUnlockedAt(now);
            }
        }
        nodeRepository.saveAll(nodes);

        // Let mastery have its say on top — it can only move things further along, and it
        // carries the unlocks down to anything the placement did not reach directly.
        roadmapService.regenerateRoadmap(userId);
        return placed;
    }

    // -------------------------------------------------------------- internals

    /**
     * The option order this question is served in: a fixed shuffle per question, so the page
     * and the marking agree without the order having to be stored anywhere.
     */
    private static List<Integer> order(GauntletBank.Question q) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < q.options().size(); i++) order.add(i);
        Collections.shuffle(order, new Random(q.id().hashCode() * 31L + 7));
        return order;
    }

    private static List<String> shuffled(GauntletBank.Question q) {
        return order(q).stream().map(i -> q.options().get(i)).toList();
    }

    /** Where the correct option (written first in the bank) ended up after shuffling. */
    static int correctIndex(GauntletBank.Question q) {
        return order(q).indexOf(0);
    }

    private static boolean isCorrect(GauntletBank.Question q, Integer picked) {
        return picked != null && picked == correctIndex(q);
    }

    private GauntletDto.ResultView toView(PlacementResult row) {
        List<GauntletDto.SectionResult> sections;
        try {
            sections = json.readValue(row.getSections(),
                new TypeReference<List<GauntletDto.SectionResult>>() {});
        } catch (Exception e) {
            sections = List.of();
        }
        return new GauntletDto.ResultView(row.getOverallRating(), sections,
            row.getNodesPlaced(), row.getCreatedAt());
    }
}
