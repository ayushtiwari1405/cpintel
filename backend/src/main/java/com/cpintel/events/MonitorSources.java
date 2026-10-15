package com.cpintel.events;

import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.GroupContest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Where a candidate's monitor heartbeats come from.
 *
 * <p>A heartbeat is a claim by a client, and anything a client sends can be sent by something
 * else: close the monitor, and replay its request from a script, or from another machine, with
 * the session's token. That cannot be prevented by the server — whatever the real monitor knows,
 * a forger holding the same session knows too — but it can be made visible. Each heartbeat's
 * source, the caller's address and the program it came from, is noted the first time it is seen
 * in a session, as a server-side row in the examination log. {@link ExamFlagService} then sets
 * those beside the candidate's sign-ins: a monitor reporting from an address the candidate never
 * signed in from, or from a different program than the one that signed in, is the shape of
 * exactly that forgery, and it goes on the suspicious-activity list for a person to look at.
 *
 * <p>Deduplicated in Redis (a set per candidate per paper, kept until a day after the paper), so
 * the log gains a row per new source rather than one every fifteen seconds. If Redis cannot be
 * reached nothing is recorded: a heartbeat must never fail for this.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonitorSources {

    private static final String PREFIX = "monitor-src:";

    private final StringRedisTemplate redis;
    private final ExamEventService examEvents;

    public void observe(GroupContest exam, Long userId, HttpServletRequest request) {
        String address = request.getRemoteAddr();
        String program = request.getHeader("User-Agent");
        String source = (address == null ? "?" : address) + " | "
            + (program == null || program.isBlank() ? "(no user agent)" : program);
        try {
            String key = PREFIX + exam.getContestId() + ":" + userId;
            Long added = redis.opsForSet().add(key, source);
            if (added == null || added == 0) return;
            Instant end = exam.getEndsAt() == null ? Instant.now() : exam.getEndsAt();
            Duration left = Duration.between(Instant.now(), end);
            redis.expire(key, (left.isNegative() ? Duration.ZERO : left).plusDays(1));
        } catch (Exception e) {
            log.debug("Could not note the monitor source for user {}: {}", userId, e.getMessage());
            return;
        }
        examEvents.recordServerSide(exam, userId, ExamEvent.Type.MONITOR_SOURCE, null,
            truncate(source, 500));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
