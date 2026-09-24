package com.cpintel.admin;

import com.cpintel.entity.AuditLog;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.mapper.UserMapper;
import com.cpintel.mail.EmailTemplates;
import com.cpintel.mail.MailService;
import com.cpintel.repository.jpa.AuditLogRepository;
import com.cpintel.repository.jpa.ExamEventRepository;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.entity.UnifiedScore;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.mongo.PersonalFileRepository;
import com.cpintel.security.JwtService;
import com.cpintel.security.Roles;
import com.cpintel.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Accounts, as an administrator sees them.
 *
 * The rules that matter are the ones that stop the console being used to break itself. An
 * admin cannot change their own role or switch off their own account — both are one click away
 * from being locked out of the screen you would need in order to undo it — and the last active
 * admin cannot be demoted or deactivated by anyone, which is the same failure with two people
 * involved instead of one.
 *
 * <p><b>What separates the two console tiers here.</b> An ADMIN runs the room: they create
 * ordinary users, correct their details, activate and deactivate them, sign them out, and set
 * a new password for somebody who has forgotten theirs. What an ADMIN cannot do is touch
 * another console account. Every write below refuses when its target holds ADMIN or
 * SUPER_ADMIN and the caller is not a super admin — because an admin who can deactivate,
 * rename or re-password a peer can remove the person who would have stopped them, which is
 * the same escalation the role-assignment rule already exists to prevent, spelled differently.
 * A SUPER_ADMIN has no such limit: every account in the deployment, admins included, is
 * theirs to change.
 *
 * <p><b>Delete is a super admin's, and it is the blunt instrument.</b> Removing an account
 * takes its submissions, files, standings and examination events with it by cascade, and no
 * amount of confirmation makes that recoverable — so it is refused for anybody who has sat an
 * examination, whose session log is evidence about a paper somebody may still need to read.
 * Deactivating stops a person signing in and leaves the history intact, which is what "remove
 * this person" almost always means; delete is for the account created by a typo an hour ago.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    /** What a super admin may set someone to. SUPER_ADMIN is not in here on purpose. */
    private static final Set<String> ROLES = Roles.ASSIGNABLE;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int ACTIVITY_ROWS = 20;

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PersonalFileRepository personalFileRepository;
    private final UnifiedScoreRepository unifiedScoreRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final JwtService jwtService;
    private final ExamEventRepository examEventRepository;
    private final MailService mail;
    private final com.cpintel.repository.mongo.CfSubmissionRepository cfSubmissionRepository;

    // ------------------------------------------------------------------ reads

    public AdminDto.UserPage list(String query, String role, Boolean active, int page, int size) {
        int capped = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Page<User> found = userRepository.findAll(
            filter(query, role, active),
            PageRequest.of(Math.max(page, 0), capped, Sort.by(Sort.Direction.DESC, "createdAt")));

        Map<Long, Instant> lastLogins = lastLogins(found.getContent());
        List<AdminDto.UserRow> rows = found.getContent().stream()
            .map(user -> toRow(user, lastLogins.get(user.getUserId())))
            .toList();

        return new AdminDto.UserPage(
            rows, found.getNumber(), found.getSize(), found.getTotalElements(), found.getTotalPages());
    }

    public AdminDto.UserDetail detail(Long userId) {
        User user = userRepository.findByIdWithPlatforms(userId)
            .orElseThrow(() -> ApiException.notFound("No such user"));

        Map<Long, Instant> lastLogin = lastLogins(List.of(user));

        List<AdminDto.AuditEntry> activity = auditLogRepository
            .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, ACTIVITY_ROWS))
            .stream()
            .map(entry -> toEntry(entry, user.getUsername()))
            .toList();

        long files = 0;
        long bytes = 0;
        try {
            var metadata = personalFileRepository.listMetadata(userId);
            files = metadata.size();
            bytes = metadata.stream()
                .mapToLong(f -> f.getSizeBytes() == null ? 0 : f.getSizeBytes())
                .sum();
        } catch (Exception e) {
            // A file store that is down should not take the whole user record with it — the
            // account state on this screen is the part an admin came here to act on.
            log.warn("Could not read file totals for user {}: {}", userId, e.getMessage());
        }

        var platforms = user.getPlatformAccounts() == null ? List.<com.cpintel.dto.PlatformDto.Summary>of()
            : user.getPlatformAccounts().stream().map(userMapper::toPlatformSummary).toList();

        return new AdminDto.UserDetail(
            toRow(user, lastLogin.get(userId)),
            user.getCountry(),
            user.getInstitution(),
            user.getAvatarUrl(),
            platforms,
            files,
            bytes,
            activity);
    }

    // ----------------------------------------------------------------- writes

    @Transactional
    @CacheEvict(value = "user_profile", key = "#targetUserId")
    public AdminDto.UserRow changeRole(Long adminId, Long targetUserId,
                                       AdminDto.RoleRequest req, HttpServletRequest httpReq) {
        String role = req.role() == null ? "" : req.role().trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role))
            throw ApiException.badRequest("Role must be USER or ADMIN");

        if (adminId != null && adminId.equals(targetUserId))
            throw ApiException.badRequest(
                "You cannot change your own role. Ask another super admin to do it.");

        User user = require(targetUserId);

        // A super admin is not demotable through the console, by anyone.
        //
        // The tier exists to be the fixed point the rest of the permission system hangs from;
        // if two super admins can demote each other then whoever clicks first owns the
        // deployment, which is a race, not a policy. Changing who is a super admin is a
        // deliberate act performed against the deployment's own configuration —
        // cpintel.admin.bootstrap-email — and not something one login can do to another.
        if (Roles.SUPER_ADMIN.equals(user.getRole()))
            throw ApiException.badRequest(
                "A super admin's role cannot be changed here. Set cpintel.admin.bootstrap-email "
                + "and restart to move it.");
        if (role.equals(user.getRole()))
            return toRow(user, lastLogins(List.of(user)).get(targetUserId));

        if ("ADMIN".equals(user.getRole()) && userRepository.countOtherActiveAdmins(targetUserId) == 0)
            throw ApiException.badRequest(
                "This is the only active admin left. Promote someone else first.");

        String previous = user.getRole();
        user.setRole(role);
        userRepository.save(user);

        // The new role only reaches the client on its next token. Retiring the ones it holds
        // now turns a promotion into something that takes effect while the person is looking
        // at the screen, rather than up to fifteen minutes later.
        jwtService.revokeUserTokens(targetUserId);

        auditService.record(adminId, AuditService.ROLE_CHANGED, "USER",
            targetUserId + ":" + previous + "->" + role, httpReq);
        log.info("Admin {} changed role of user {} from {} to {}", adminId, targetUserId, previous, role);

        return toRow(user, lastLogins(List.of(user)).get(targetUserId));
    }

    /**
     * Creates an account, because with self-registration closed nothing else does.
     *
     * The password is chosen by the super admin and handed over out of band. That is a real
     * weakness of admin-created accounts and worth naming: the person who made the account
     * knows the password until the owner changes it. It is still preferable to the
     * alternatives here — a default password baked into the deployment, or an invite-mail
     * flow that this system has no mail transport for.
     *
     * <p><b>Both console tiers may create accounts; only a super admin may create an ADMIN.</b>
     * An admin running a contest needs to be able to add the people sitting it, and making that
     * a super admin's job turns every new participant into a request to somebody else. Creating
     * a participant hands out no privilege, so there is nothing for the higher tier to protect.
     *
     * <p>Creating an <em>admin</em> is the opposite, and stays reserved. An admin who could mint
     * another admin could mint one for themselves and hold two accounts, which is the same
     * self-promotion the role-assignment rule exists to prevent — just spelled differently. So
     * the tier check is on the role being created rather than on the act of creating.
     */
    @Transactional
    public AdminDto.UserRow createUser(Long adminId, AdminDto.CreateUserRequest req,
                                       boolean callerIsSuperAdmin, HttpServletRequest httpReq) {
        String role = req.role() == null ? Roles.USER : req.role().trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role))
            throw ApiException.badRequest("Role must be USER or ADMIN");

        if (!Roles.USER.equals(role) && !callerIsSuperAdmin) {
            throw ApiException.forbidden(
                "Only a super admin may create an " + role + " account. You can create "
                    + "ordinary user accounts.");
        }

        String email = req.email().trim().toLowerCase(Locale.ROOT);
        String username = req.username().trim();

        if (userRepository.existsByEmail(email))
            throw ApiException.conflict("Email already registered");
        if (userRepository.existsByUsername(username))
            throw ApiException.conflict("Username already taken");

        User user = userRepository.save(User.builder()
            .username(username)
            .email(email)
            .passwordHash(passwordEncoder.encode(req.password()))
            .fullName(StringUtils.hasText(req.fullName()) ? req.fullName().trim() : null)
            .role(role)
            .isActive(true)
            // Not verified: an admin typing an address is not evidence that it belongs to the
            // person, and the account works without it exactly as a self-registered one does.
            .isVerified(false)
            .build());

        // Mirrors registration, which creates this row in the same transaction rather than
        // leaving it to a trigger. An account without it has no unified score to write into.
        unifiedScoreRepository.save(UnifiedScore.builder().user(user).build());

        auditService.record(adminId, AuditService.USER_CREATED, "USER",
            user.getUserId() + ":" + role, httpReq);
        log.info("Admin {} created user {} ({}) as {}",
            adminId, user.getUserId(), email, role);

        return toRow(user, null);
    }

    /**
     * Corrects somebody's details.
     *
     * A changed email un-verifies the account, because the verification was evidence about the
     * old address and says nothing about the new one. Nothing here can change what an account
     * may do — role and activation have their own endpoints, with their own rules.
     */
    @Transactional
    @CacheEvict(value = "user_profile", key = "#targetUserId")
    public AdminDto.UserRow updateUser(Long adminId, Long targetUserId,
                                       AdminDto.UpdateUserRequest req,
                                       boolean callerIsSuperAdmin,
                                       HttpServletRequest httpReq) {
        User user = require(targetUserId);
        requireMayTouch(user, callerIsSuperAdmin, "change the details of");

        if (StringUtils.hasText(req.email())) {
            String email = req.email().trim().toLowerCase(Locale.ROOT);
            if (!email.equals(user.getEmail())) {
                if (userRepository.existsByEmail(email)) {
                    throw ApiException.conflict("Email already registered");
                }
                user.setEmail(email);
                user.setIsVerified(false);
            }
        }
        if (req.fullName() != null) {
            user.setFullName(StringUtils.hasText(req.fullName()) ? req.fullName().trim() : null);
        }
        if (req.institution() != null) {
            user.setInstitution(
                StringUtils.hasText(req.institution()) ? req.institution().trim() : null);
        }
        if (req.country() != null) {
            user.setCountry(StringUtils.hasText(req.country()) ? req.country().trim() : null);
        }

        userRepository.save(user);
        auditService.record(adminId, AuditService.USER_UPDATED, "USER",
            String.valueOf(targetUserId), httpReq);

        return toRow(user, lastLogins(List.of(user)).get(targetUserId));
    }

    @Transactional
    @CacheEvict(value = "user_profile", key = "#targetUserId")
    public AdminDto.UserRow setActive(Long adminId, Long targetUserId,
                                      AdminDto.ActiveRequest req, boolean callerIsSuperAdmin,
                                      HttpServletRequest httpReq) {
        boolean active = Boolean.TRUE.equals(req.active());

        if (!active && adminId != null && adminId.equals(targetUserId))
            throw ApiException.badRequest("You cannot deactivate your own account.");

        User user = require(targetUserId);
        requireMayTouch(user, callerIsSuperAdmin, active ? "reactivate" : "deactivate");

        // A super admin cannot be switched off from here, by anyone — including another super
        // admin. The tier is the fixed point the rest of the permission system hangs from, and
        // two people who can deactivate each other turn that into a race. Moving it means
        // changing cpintel.admin.bootstrap-email and restarting, which is the same answer
        // changeRole gives.
        if (!active && Roles.SUPER_ADMIN.equals(user.getRole()))
            throw ApiException.badRequest(
                "A super admin cannot be deactivated here. Set cpintel.admin.bootstrap-email "
                + "and restart to move that role.");

        if (Boolean.valueOf(active).equals(user.getIsActive()))
            return toRow(user, lastLogins(List.of(user)).get(targetUserId));

        if (!active && "ADMIN".equals(user.getRole())
            && userRepository.countOtherActiveAdmins(targetUserId) == 0)
            throw ApiException.badRequest(
                "This is the only active admin left. Promote someone else first.");

        user.setIsActive(active);
        userRepository.save(user);

        if (!active) endSessions(targetUserId);

        auditService.record(adminId,
            active ? AuditService.USER_ACTIVATED : AuditService.USER_DEACTIVATED,
            "USER",
            StringUtils.hasText(req.reason()) ? targetUserId + ":" + req.reason() : String.valueOf(targetUserId),
            httpReq);
        log.info("Admin {} set user {} active={}", adminId, targetUserId, active);

        return toRow(user, lastLogins(List.of(user)).get(targetUserId));
    }

    /** Signs a user out of every device without touching their account otherwise. */
    @Transactional
    public void revokeSessions(Long adminId, Long targetUserId, boolean callerIsSuperAdmin,
                               HttpServletRequest httpReq) {
        requireMayTouch(require(targetUserId), callerIsSuperAdmin, "sign out");
        endSessions(targetUserId);
        auditService.record(adminId, AuditService.SESSIONS_REVOKED, "USER",
            String.valueOf(targetUserId), httpReq);
        log.info("Admin {} revoked sessions for user {}", adminId, targetUserId);
    }

    /**
     * Sets a new password on somebody else's account.
     *
     * <p>For the person who has forgotten theirs and cannot reach their own mail, which on a
     * deployment that hands out accounts on a printed sheet is a common enough morning. The
     * self-service route — a link emailed to the address on the account — is the better one
     * and is what {@code /auth/forgot-password} is for; this exists because it does not always
     * work, and "ask an administrator" has to lead somewhere.
     *
     * <p><b>The password is returned once and never emailed.</b> It is handed over the same way
     * the first one was. Mailing it would put a live credential in a mailbox, which is the
     * thing the reset-link flow exists to avoid, and a deployment with no SMTP would have no
     * path at all.
     *
     * <p>Everything the owner had open is closed, and they are told it happened — by mail if
     * there is any, with no password and no link in it. An administrator quietly taking over
     * an account should not be something only the audit log knows about.
     */
    @Transactional
    @CacheEvict(value = "user_profile", key = "#targetUserId")
    public AdminDto.GeneratedPassword setPassword(Long adminId, Long targetUserId,
                                                  AdminDto.SetPasswordRequest req,
                                                  boolean callerIsSuperAdmin,
                                                  HttpServletRequest httpReq) {
        User user = require(targetUserId);
        requireMayTouch(user, callerIsSuperAdmin, "set the password of");

        if (adminId != null && adminId.equals(targetUserId)) {
            throw ApiException.badRequest(
                "Change your own password from your profile, where the current one is asked "
                + "for.");
        }

        String password = StringUtils.hasText(req.password())
            ? req.password() : generatePassword();
        if (password.length() < 8) {
            throw ApiException.badRequest("A password has to be at least 8 characters.");
        }

        user.setPasswordHash(passwordEncoder.encode(password));
        // Deliberately cleared rather than stamped. This password is not one the owner chose,
        // so the account goes back to reading as "still the password somebody was given" —
        // which is exactly what it is.
        user.setPasswordChangedAt(null);
        userRepository.save(user);
        endSessions(targetUserId);

        EmailTemplates.Message notice = EmailTemplates.passwordResetByAdmin(user.getFullName());
        mail.send(user.getEmail(), notice.subject(), notice.text(), notice.html());

        auditService.record(adminId, AuditService.PASSWORD_SET_BY_ADMIN, "USER",
            StringUtils.hasText(req.reason())
                ? targetUserId + ":" + req.reason() : String.valueOf(targetUserId),
            httpReq);
        log.info("Admin {} set a new password for user {}", adminId, targetUserId);

        return new AdminDto.GeneratedPassword(targetUserId, user.getUsername(), password);
    }

    /**
     * Deletes an account outright. Super admin only, and refused where it would lose evidence.
     *
     * <p>This is the blunt instrument and is meant to stay that way. The cascade takes the
     * person's submissions, files, standings and examination events with them, and none of it
     * comes back. Deactivating is what "remove this person" almost always means — they cannot
     * sign in, and everything they did is still readable.
     *
     * <p>So delete is refused for anybody who has sat an examination. Their session log is
     * evidence about a paper, possibly one still being marked or appealed, and an account
     * deletion is not a decision that should also be a decision to destroy that. The account
     * created by a typo an hour ago has none of it, which is the case this is for.
     */
    @Transactional
    @CacheEvict(value = "user_profile", key = "#targetUserId")
    public void deleteUser(Long adminId, Long targetUserId, HttpServletRequest httpReq) {
        if (adminId != null && adminId.equals(targetUserId))
            throw ApiException.badRequest("You cannot delete your own account.");

        User user = require(targetUserId);

        if (Roles.SUPER_ADMIN.equals(user.getRole()))
            throw ApiException.badRequest(
                "A super admin cannot be deleted here. Set cpintel.admin.bootstrap-email and "
                + "restart to move that role first.");

        if (Roles.ADMIN.equals(user.getRole())
            && userRepository.countOtherActiveAdmins(targetUserId) == 0)
            throw ApiException.badRequest(
                "This is the only active admin left. Promote someone else first.");

        long examEvents = examEventRepository.countByUserUserId(targetUserId);
        if (examEvents > 0)
            throw ApiException.badRequest(
                "This person has sat an examination, and deleting the account would delete its "
                + "session log with it. Deactivate them instead — they cannot sign in, and the "
                + "record stays readable.");

        endSessions(targetUserId);
        userRepository.delete(user);
        // Mongo has no foreign key to cascade through. Synced platform history is re-derivable
        // and belongs to nobody once the account is gone; left behind, it is orphaned rows
        // that no screen can reach.
        cfSubmissionRepository.deleteByUserId(targetUserId);

        auditService.record(adminId, AuditService.USER_DELETED, "USER",
            targetUserId + ":" + user.getUsername(), httpReq);
        log.info("Super admin {} deleted user {} ({})", adminId, targetUserId,
            user.getUsername());
    }

    /**
     * Refuses an ordinary admin acting on another console account.
     *
     * <p>An admin who can deactivate, rename or re-password a peer can remove the person who
     * would have stopped them, and an admin who can do it to a super admin can remove the tier
     * that limits them at all. That is the same escalation {@code changeRole} is reserved for,
     * arrived at from a different screen — so the check lives on every write rather than on the
     * one that looks dangerous.
     *
     * <p>A super admin passes through unconditionally. Every account in the deployment, admins
     * included, is theirs to change; that is what the tier is for.
     */
    private void requireMayTouch(User target, boolean callerIsSuperAdmin, String verb) {
        if (callerIsSuperAdmin) return;
        if (!Roles.isAdminLevel(target.getRole())) return;
        throw ApiException.forbidden(
            "Only a super admin may " + verb + " another administrator's account.");
    }

    /**
     * A password that can be read off a screen and typed once.
     *
     * Four groups of four from an alphabet with no {@code O}, {@code 0}, {@code I} or
     * {@code 1} in it, because this is read aloud or copied off a printout by somebody who is
     * about to type it into a machine that will not tell them which character they got wrong.
     */
    private String generatePassword() {
        final char[] alphabet = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
            .toCharArray();
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder(19);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) out.append('-');
            out.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return out.toString();
    }

    /**
     * Both halves of ending a session, always together.
     *
     * Revoking the refresh tokens is the durable half and stops anything new being minted;
     * retiring the access tokens closes the window in which one already in a browser would
     * still be honoured. Either alone leaves the account partly signed in.
     */
    private void endSessions(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
        jwtService.revokeUserTokens(userId);
    }

    // ---------------------------------------------------------------- helpers

    private User require(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("No such user"));
    }

    /**
     * Search across the fields someone would actually type into the box — a username, an
     * email, a real name — with the role and status filters applied as separate predicates so
     * they combine rather than compete.
     */
    private Specification<User> filter(String query, String role, Boolean active) {
        return (root, criteria, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(query)) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("username")), like),
                    cb.like(cb.lower(root.get("email")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("fullName"), "")), like)));
            }
            if (StringUtils.hasText(role))
                predicates.add(cb.equal(root.get("role"), role.trim().toUpperCase(Locale.ROOT)));
            if (active != null)
                predicates.add(cb.equal(root.get("isActive"), active));

            return predicates.isEmpty() ? cb.conjunction()
                : cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    /** One query for a whole page of users rather than one per row. */
    private Map<Long, Instant> lastLogins(List<User> users) {
        if (users.isEmpty()) return Map.of();
        List<Long> ids = users.stream().map(User::getUserId).toList();
        Map<Long, Instant> byUser = new HashMap<>();
        for (Object[] row : auditLogRepository.lastLoginFor(ids)) {
            byUser.put((Long) row[0], (Instant) row[1]);
        }
        return byUser;
    }

    private AdminDto.UserRow toRow(User user, Instant lastLoginAt) {
        return new AdminDto.UserRow(
            user.getUserId(),
            user.getUsername(),
            user.getEmail(),
            user.getFullName(),
            user.getRole(),
            Boolean.TRUE.equals(user.getIsActive()),
            Boolean.TRUE.equals(user.getIsVerified()),
            user.getCreatedAt(),
            lastLoginAt,
            user.getPasswordChangedAt());
    }

    static AdminDto.AuditEntry toEntry(AuditLog entry, String username) {
        return new AdminDto.AuditEntry(
            entry.getLogId(),
            entry.getUserId(),
            username,
            entry.getAction(),
            entry.getEntityType(),
            entry.getEntityId(),
            entry.getIpAddress(),
            entry.getUserAgent(),
            entry.getCreatedAt());
    }
}
