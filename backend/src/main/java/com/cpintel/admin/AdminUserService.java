package com.cpintel.admin;

import com.cpintel.entity.AuditLog;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.AuditLogRepository;
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
 * There is no delete. Removing an account would take its contests, submissions and files with
 * it by cascade, and no amount of confirmation makes that recoverable; deactivating stops
 * someone signing in and leaves the history intact, which is what "remove this person" almost
 * always means in practice.
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
     * A new account may be created directly as an ADMIN. That is not a way around the rule
     * that only super admins assign roles: creating one already requires SUPER_ADMIN, so
     * allowing it here removes a pointless second step rather than a check.
     */
    @Transactional
    public AdminDto.UserRow createUser(Long adminId, AdminDto.CreateUserRequest req,
                                       HttpServletRequest httpReq) {
        String role = req.role() == null ? Roles.USER : req.role().trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role))
            throw ApiException.badRequest("Role must be USER or ADMIN");

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
        log.info("Super admin {} created user {} ({}) as {}",
            adminId, user.getUserId(), email, role);

        return toRow(user, null);
    }

    @Transactional
    @CacheEvict(value = "user_profile", key = "#targetUserId")
    public AdminDto.UserRow setActive(Long adminId, Long targetUserId,
                                      AdminDto.ActiveRequest req, HttpServletRequest httpReq) {
        boolean active = Boolean.TRUE.equals(req.active());

        if (!active && adminId != null && adminId.equals(targetUserId))
            throw ApiException.badRequest("You cannot deactivate your own account.");

        User user = require(targetUserId);
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
    public void revokeSessions(Long adminId, Long targetUserId, HttpServletRequest httpReq) {
        require(targetUserId);
        endSessions(targetUserId);
        auditService.record(adminId, AuditService.SESSIONS_REVOKED, "USER",
            String.valueOf(targetUserId), httpReq);
        log.info("Admin {} revoked sessions for user {}", adminId, targetUserId);
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
            lastLoginAt);
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
