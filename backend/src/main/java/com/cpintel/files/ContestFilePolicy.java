package com.cpintel.files;

import com.cpintel.entity.mongo.ContestFileRule;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.mongo.ContestFileRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether a contest lets contestants reach their personal files.
 *
 * Three layers, most specific first: a rule for this contest, then a rule for the whole
 * deployment, then the configured default. Today only the last one is in play — the default
 * ships enabled and no rows exist — so every contest allows personal files, and an admin who
 * needs to close one (a proctored round, a judge that forbids reference material) writes a
 * single rule without a redeploy.
 *
 * Whether the *feature* exists is a deployment decision; whether a *contest* uses it is an
 * admin decision. Keeping them apart is what lets the config default stay "on" while a
 * particular round is off, which is the shape every real request for this takes.
 *
 * A database that cannot be reached falls back to the configured default rather than failing
 * the contest page. The consequence of guessing wrong here is a panel that should have been
 * hidden, which during a live round beats a contest that will not load.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContestFilePolicy {

    private final ContestFileRuleRepository repo;

    @Value("${cpintel.files.contest-access.enabled-by-default:true}")
    private boolean configDefault;

    // ------------------------------------------------------------------ read

    /** True when this contest may serve personal files to its contestants. */
    public boolean enabledFor(String platform, String contestId) {
        if (contestId != null) {
            Optional<ContestFileRule> rule = find(platform, contestId);
            if (rule.isPresent()) return Boolean.TRUE.equals(rule.get().getEnabled());
        }
        return defaultEnabled();
    }

    /** Whether this contest has a rule of its own, rather than following the default. */
    public boolean hasRule(String platform, String contestId) {
        return contestId != null && find(platform, contestId).isPresent();
    }

    /** The default in force — an admin's deployment-wide rule, or the configured value. */
    public boolean defaultEnabled() {
        return find(ContestFileRule.GLOBAL, null)
            .map(rule -> Boolean.TRUE.equals(rule.getEnabled()))
            .orElse(configDefault);
    }

    /**
     * Throws unless this contest allows personal files.
     *
     * The message names the contest rather than the feature: a contestant who cannot see
     * their notebook needs to know it was this round that closed it, not that CPIntel is
     * broken.
     */
    public void require(String platform, String contestId) {
        if (!enabledFor(platform, contestId)) {
            throw ApiException.forbidden(
                "Personal files are turned off for this contest.");
        }
    }

    public FilesDto.PolicyOverview overview() {
        List<FilesDto.ContestRule> rules = new ArrayList<>();
        boolean fromConfig = true;
        for (ContestFileRule row : repo.findAllByOrderByUpdatedAtDesc()) {
            if (ContestFileRule.GLOBAL.equals(row.getPlatform()) && row.getContestId() == null) {
                fromConfig = false;
                continue;
            }
            rules.add(toDto(row));
        }
        return new FilesDto.PolicyOverview(defaultEnabled(), fromConfig, rules);
    }

    // ----------------------------------------------------------------- write

    /** Sets one contest's rule, overriding the default for it alone. */
    public FilesDto.ContestRule setRule(String platform, String contestId, Long adminId,
                                        FilesDto.RuleRequest req) {
        return toDto(save(platform, contestId, adminId, req.enabled(), req.note()));
    }

    /** Drops a contest's rule so it follows the default again. */
    public void clearRule(String platform, String contestId) {
        repo.deleteByPlatformAndContestId(platform, contestId);
    }

    /** Moves the deployment-wide default without a redeploy. */
    public FilesDto.PolicyOverview setDefault(Long adminId, FilesDto.DefaultRequest req) {
        save(ContestFileRule.GLOBAL, null, adminId, req.enabled(), req.note());
        return overview();
    }

    /** Reverts to the default from configuration. */
    public FilesDto.PolicyOverview clearDefault() {
        repo.deleteByPlatformAndContestId(ContestFileRule.GLOBAL, null);
        return overview();
    }

    // --------------------------------------------------------------- helpers

    private Optional<ContestFileRule> find(String platform, String contestId) {
        try {
            return repo.findByPlatformAndContestId(platform, contestId);
        } catch (Exception e) {
            log.warn("Contest file rule lookup failed ({} {}): {}",
                platform, contestId, e.getMessage());
            return Optional.empty();
        }
    }

    private ContestFileRule save(String platform, String contestId, Long adminId,
                                 boolean enabled, String note) {
        ContestFileRule row = repo.findByPlatformAndContestId(platform, contestId)
            .orElseGet(() -> ContestFileRule.builder()
                .platform(platform)
                .contestId(contestId)
                .build());
        row.setEnabled(enabled);
        row.setNote(note == null || note.isBlank() ? null : note.trim());
        row.setUpdatedBy(adminId);
        row.setUpdatedAt(Instant.now());
        return repo.save(row);
    }

    private FilesDto.ContestRule toDto(ContestFileRule row) {
        return new FilesDto.ContestRule(row.getPlatform(), row.getContestId(),
            Boolean.TRUE.equals(row.getEnabled()), row.getNote(), row.getUpdatedBy(),
            row.getUpdatedAt());
    }
}
