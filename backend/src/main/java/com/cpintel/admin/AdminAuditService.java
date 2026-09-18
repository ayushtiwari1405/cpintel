package com.cpintel.admin;

import com.cpintel.entity.AuditLog;
import com.cpintel.entity.User;
import com.cpintel.repository.jpa.AuditLogRepository;
import com.cpintel.repository.jpa.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads the audit trail back for the console.
 *
 * Rows are stored with a user id and nothing else, so names are resolved a page at a time
 * rather than by joining — the trail deliberately outlives the accounts it mentions, and a
 * join would quietly drop the history of anyone since removed.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AdminDto.AuditPage list(String action, Long userId, Instant since, int page, int size) {
        int capped = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Page<AuditLog> found = auditLogRepository.findAll(
            filter(action, userId, since),
            PageRequest.of(Math.max(page, 0), capped, Sort.by(Sort.Direction.DESC, "createdAt")));

        Map<Long, String> names = usernames(found.getContent());
        List<AdminDto.AuditEntry> entries = found.getContent().stream()
            .map(entry -> AdminUserService.toEntry(entry,
                entry.getUserId() == null ? null : names.get(entry.getUserId())))
            .toList();

        return new AdminDto.AuditPage(
            entries,
            found.getNumber(),
            found.getSize(),
            found.getTotalElements(),
            found.getTotalPages(),
            auditLogRepository.distinctActions());
    }

    /** The newest entries, for the overview screen. */
    public List<AdminDto.AuditEntry> recent(int limit) {
        List<AuditLog> rows = auditLogRepository.findAllByOrderByCreatedAtDesc(
            PageRequest.of(0, Math.clamp(limit, 1, 50)));
        Map<Long, String> names = usernames(rows);
        return rows.stream()
            .map(entry -> AdminUserService.toEntry(entry,
                entry.getUserId() == null ? null : names.get(entry.getUserId())))
            .toList();
    }

    private Specification<AuditLog> filter(String action, Long userId, Instant since) {
        return (root, criteria, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(action))
                predicates.add(cb.equal(root.get("action"), action.trim().toUpperCase(Locale.ROOT)));
            if (userId != null)
                predicates.add(cb.equal(root.get("userId"), userId));
            if (since != null)
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), since));
            return predicates.isEmpty() ? cb.conjunction()
                : cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private Map<Long, String> usernames(List<AuditLog> entries) {
        List<Long> ids = entries.stream()
            .map(AuditLog::getUserId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        if (ids.isEmpty()) return Map.of();
        return userRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(User::getUserId, User::getUsername, (a, b) -> a));
    }
}
