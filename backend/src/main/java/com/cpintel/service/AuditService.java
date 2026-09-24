package com.cpintel.service;

import com.cpintel.entity.AuditLog;
import com.cpintel.repository.jpa.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit trail.
 *
 * Two properties matter more than anything else here, and both are deliberate:
 *
 * Recording runs in its own transaction. A failed login is exactly the event most worth
 * keeping, and it is raised by throwing out of a transaction that then rolls back — an audit
 * row enlisted in that transaction would vanish along with it.
 *
 * And a failure to record never fails the action being recorded. Losing one row of history is
 * a smaller problem than a login that stops working because the audit table is unavailable.
 * The exception is logged rather than swallowed silently, so a broken trail is still visible.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    // Auth
    public static final String LOGIN         = "LOGIN";
    public static final String LOGIN_FAILED  = "LOGIN_FAILED";
    /** Sign-in refused without checking the password, because the account was being guessed at. */
    public static final String LOGIN_THROTTLED = "LOGIN_THROTTLED";
    public static final String LOGOUT        = "LOGOUT";
    public static final String REGISTER      = "REGISTER";
    /** A sign-up attempt that arrived while self-registration was closed. */
    public static final String REGISTER_BLOCKED = "REGISTER_BLOCKED";

    // Passwords. Recorded against the account in every case, because the interesting reading
    // is a run of them: a reset asked for repeatedly, or a change nobody remembers making.
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET           = "PASSWORD_RESET";
    public static final String PASSWORD_CHANGED         = "PASSWORD_CHANGED";
    /** Somebody signed in gave the wrong current password while trying to change it. */
    public static final String PASSWORD_CHANGE_FAILED   = "PASSWORD_CHANGE_FAILED";
    /** An administrator set somebody else's password. Never records the password. */
    public static final String PASSWORD_SET_BY_ADMIN    = "ADMIN_PASSWORD_SET";
    /** A super admin deleted an account outright. */
    public static final String USER_DELETED             = "ADMIN_USER_DELETED";

    // Examination access. The passwords themselves never appear in any of these.
    public static final String EXAM_PASSWORD_SET     = "ADMIN_EXAM_PASSWORD_SET";
    public static final String EXAM_PASSWORD_CLEARED = "ADMIN_EXAM_PASSWORD_CLEARED";
    public static final String EXAM_PASSCODES_ISSUED = "ADMIN_EXAM_PASSCODES_ISSUED";
    public static final String EXAM_PASSCODES_READ   = "ADMIN_EXAM_PASSCODES_READ";
    public static final String EXAM_UNLOCKED         = "EXAM_UNLOCKED";
    /** Signed in with an examination password, into examination mode. */
    public static final String EXAM_SIGN_IN          = "EXAM_SIGN_IN";
    public static final String EXAM_UNLOCK_REFUSED   = "EXAM_UNLOCK_REFUSED";

    // Admin actions on accounts
    public static final String ROLE_CHANGED       = "ADMIN_ROLE_CHANGED";
    public static final String USER_ACTIVATED     = "ADMIN_USER_ACTIVATED";
    public static final String USER_DEACTIVATED   = "ADMIN_USER_DEACTIVATED";
    public static final String SESSIONS_REVOKED   = "ADMIN_SESSIONS_REVOKED";
    public static final String ADMIN_BOOTSTRAPPED = "ADMIN_BOOTSTRAPPED";
    /** A super admin created an account by hand — the only way in while sign-up is closed. */
    public static final String USER_CREATED       = "ADMIN_USER_CREATED";

    // Admin actions on groups and the contests laid over them
    public static final String GROUP_CREATED         = "ADMIN_GROUP_CREATED";
    public static final String GROUP_UPDATED         = "ADMIN_GROUP_UPDATED";
    public static final String GROUP_DEACTIVATED     = "ADMIN_GROUP_DEACTIVATED";
    public static final String GROUP_MEMBER_ADDED    = "ADMIN_GROUP_MEMBER_ADDED";
    public static final String GROUP_MEMBER_UPDATED  = "ADMIN_GROUP_MEMBER_UPDATED";
    public static final String GROUP_MEMBER_REMOVED  = "ADMIN_GROUP_MEMBER_REMOVED";
    public static final String GROUP_MEMBER_MOVED    = "ADMIN_GROUP_MEMBER_MOVED";
    public static final String USER_UPDATED          = "ADMIN_USER_UPDATED";
    public static final String GROUP_CONTEST_ADDED   = "ADMIN_GROUP_CONTEST_ADDED";
    public static final String GROUP_CONTEST_REMOVED = "ADMIN_GROUP_CONTEST_REMOVED";

    // Admin actions on contests and examinations
    public static final String EVENT_CREATED     = "ADMIN_EVENT_CREATED";
    public static final String EVENT_UPDATED     = "ADMIN_EVENT_UPDATED";
    public static final String EVENT_DELETED     = "ADMIN_EVENT_DELETED";
    public static final String EVENT_LIFECYCLE   = "ADMIN_EVENT_LIFECYCLE";
    public static final String EVENT_ASSIGNED    = "ADMIN_EVENT_ASSIGNED";
    public static final String EVENT_UNASSIGNED  = "ADMIN_EVENT_UNASSIGNED";
    public static final String EVENT_PROBLEMS    = "ADMIN_EVENT_PROBLEMS";

    // Admin actions on the contest file policy
    public static final String FILE_POLICY_DEFAULT = "ADMIN_FILE_POLICY_DEFAULT";
    public static final String FILE_POLICY_RULE    = "ADMIN_FILE_POLICY_RULE";
    public static final String FILE_POLICY_CLEARED = "ADMIN_FILE_POLICY_CLEARED";

    private static final int MAX_ENTITY_ID = 100;
    private static final int MAX_USER_AGENT = 500;
    private static final int MAX_IP = 45;

    private final AuditLogRepository auditLogRepository;

    /**
     * Records an action with no request context — schedulers, startup tasks.
     *
     * The transaction annotation sits on the entry points rather than on the shared helper
     * below, because a call from one method of this class to another never passes through the
     * proxy that would start the new transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, String action, String entityType, String entityId) {
        write(userId, action, entityType, entityId, null, null);
    }

    /** Records an action along with where it came from. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, String action, String entityType, String entityId,
                       HttpServletRequest request) {
        write(userId, action, entityType, entityId,
            request == null ? null : request.getRemoteAddr(),
            request == null ? null : request.getHeader("User-Agent"));
    }

    private void write(Long userId, String action, String entityType, String entityId,
                       String ip, String userAgent) {
        try {
            auditLogRepository.save(AuditLog.builder()
                .userId(userId)
                .action(action)
                .entityType(entityType)
                .entityId(clip(entityId, MAX_ENTITY_ID))
                .ipAddress(clip(ip, MAX_IP))
                .userAgent(clip(userAgent, MAX_USER_AGENT))
                .build());
        } catch (Exception e) {
            log.warn("Could not write audit entry {} for user {}: {}", action, userId, e.getMessage());
        }
    }

    private String clip(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
