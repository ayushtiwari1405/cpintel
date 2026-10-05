package com.cpintel.evaluation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which TAs froze their marking on an examination, and which answers an admin reopened.
 *
 * <p>Answers are keyed as {@code "<userId>:<label>"}, the same key the sheet uses.
 */
@Component
@RequiredArgsConstructor
public class EvaluationLocks {

    private final JdbcTemplate jdbc;

    public static String key(Long userId, String label) {
        return userId + ":" + label;
    }

    /** TA id to when they froze, for one examination. */
    public Map<Long, Instant> freezes(Long eventId) {
        Map<Long, Instant> out = new HashMap<>();
        jdbc.query("SELECT ta_user_id, frozen_at FROM exam_ta_freezes WHERE contest_id = ?",
            rs -> { out.put(rs.getLong(1), rs.getTimestamp(2).toInstant()); }, eventId);
        return out;
    }

    public void freeze(Long eventId, Long taId) {
        jdbc.update("""
            INSERT INTO exam_ta_freezes (contest_id, ta_user_id, frozen_at) VALUES (?, ?, ?)
            ON CONFLICT (contest_id, ta_user_id) DO UPDATE SET frozen_at = EXCLUDED.frozen_at
            """, eventId, taId, Timestamp.from(Instant.now()));
    }

    public boolean unfreeze(Long eventId, Long taId) {
        return jdbc.update("DELETE FROM exam_ta_freezes WHERE contest_id = ? AND ta_user_id = ?",
            eventId, taId) > 0;
    }

    /** A TA taken out of a classroom is not left frozen there if they come back. */
    public void unfreezeInClassroom(Long classroomId, Long taId) {
        jdbc.update("""
            DELETE FROM exam_ta_freezes f USING group_contests c
             WHERE f.contest_id = c.contest_id AND c.classroom_id = ? AND f.ta_user_id = ?
            """, classroomId, taId);
    }

    public Set<String> reopened(Long eventId) {
        Set<String> out = new HashSet<>();
        jdbc.query("SELECT user_id, problem_label FROM exam_mark_reopens WHERE contest_id = ?",
            rs -> { out.add(key(rs.getLong(1), rs.getString(2))); }, eventId);
        return out;
    }

    public void reopen(Long eventId, Long userId, String label, Long adminId) {
        jdbc.update("""
            INSERT INTO exam_mark_reopens (contest_id, user_id, problem_label, reopened_by)
            VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
            """, eventId, userId, label, adminId);
    }

    public boolean close(Long eventId, Long userId, String label) {
        return jdbc.update("""
            DELETE FROM exam_mark_reopens WHERE contest_id = ? AND user_id = ? AND problem_label = ?
            """, eventId, userId, label) > 0;
    }

    /** Closes these answers, given as keys. */
    public void closeAll(Long eventId, Collection<String> keys) {
        for (String k : keys) {
            int at = k.indexOf(':');
            close(eventId, Long.parseLong(k.substring(0, at)), k.substring(at + 1));
        }
    }

    // --------------------------------------------------------------- change history

    public static final String REOPENED = "REOPENED";
    public static final String ADMIN_CHANGED = "ADMIN_CHANGED";

    public void recordChange(Long eventId, Long taId, Long userId, String label, String kind,
                             Long adminId) {
        jdbc.update("""
            INSERT INTO exam_eval_changes (contest_id, ta_user_id, user_id, problem_label, kind,
                                           changed_by)
            VALUES (?, ?, ?, ?, ?, ?)
            """, eventId, taId, userId, label, kind, adminId);
    }

    /** Per TA, per kind: how many distinct answers needed that change. */
    public Map<Long, Map<String, Integer>> changeCounts(Long eventId) {
        Map<Long, Map<String, Integer>> out = new HashMap<>();
        jdbc.query("""
            SELECT ta_user_id, kind, count(DISTINCT (user_id, problem_label))
              FROM exam_eval_changes WHERE contest_id = ?
             GROUP BY ta_user_id, kind
            """, rs -> {
                out.computeIfAbsent(rs.getLong(1), k -> new HashMap<>())
                    .put(rs.getString(2), rs.getInt(3));
            }, eventId);
        return out;
    }

    /** Per TA: distinct answers that needed any change at all. */
    public Map<Long, Integer> totalChanges(Long eventId) {
        Map<Long, Integer> out = new HashMap<>();
        jdbc.query("""
            SELECT ta_user_id, count(DISTINCT (user_id, problem_label))
              FROM exam_eval_changes WHERE contest_id = ? GROUP BY ta_user_id
            """, rs -> { out.put(rs.getLong(1), rs.getInt(2)); }, eventId);
        return out;
    }
}
