package com.cpintel.roadmap;

import java.util.Map;
import java.util.Optional;

/**
 * Where an attempt at the gauntlet lives while it is being taken.
 *
 * <p>An attempt is what makes the answers count: each question's first checked answer is
 * recorded in it and cannot be changed, and the placement is computed from it alone. Without
 * one, the check route was a free answer key — ask it about every question, then submit the
 * right answers.
 */
public interface GauntletAttempts {

    /** Question id → the option index first checked for it. */
    record Attempt(Long userId, Map<String, Integer> answers) {}

    void save(String attemptId, Attempt attempt);

    Optional<Attempt> find(String attemptId);

    void delete(String attemptId);
}
