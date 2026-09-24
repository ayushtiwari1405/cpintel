package com.cpintel.groups;

import com.cpintel.entity.GroupMember;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.domjudge.DomjudgeAccountService;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import com.cpintel.integration.domjudge.DomjudgeDto;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * New DOMjudge passwords for a whole group at once.
 *
 * <p>When the judge's passwords are regenerated for a round, every login attached in CPIntel
 * stops working together, and fixing them one dialog at a time is the job this replaces. The
 * admin pastes {@code djUsername,djPassword} — the same list the judge hands out — and each row
 * is matched to the group member that login is attached to.
 *
 * <p><b>Matched by the attached login, not by CPIntel username.</b> An account's CPIntel
 * username is often, but not always, its DOMjudge one; the attached login is the fact that
 * actually says whose password a row is. Only when a member has nothing attached — typically
 * because the stored login expired — does the row fall back to a member whose CPIntel username
 * is the login, and then the login is attached afresh.
 *
 * <p>Scoped to one group so that a paste can only touch the people the admin is looking at,
 * and so the lookup is the group's members rather than every credential on the server.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DomjudgePasswordImportService {

    private final ContestGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final DomjudgeCredentialStore credentials;
    private final DomjudgeAccountService domjudgeAccounts;

    public enum RowStatus {
        /** Preview: the judge accepted the new password; it will replace the stored one. */
        WILL_CHANGE,
        /** Preview: nothing attached, but a member of that name will have it attached. */
        WILL_ATTACH,
        /** The stored password was replaced. */
        CHANGED,
        /** The login was attached to the member of that name. */
        ATTACHED,
        /** No member of this group has that login, attached or by name. */
        NOT_FOUND,
        /** The same login appears earlier in the paste. */
        DUPLICATE,
        /** Nothing changed; the message says why. */
        FAILED
    }

    /** One row's plan or outcome. The password is never echoed back. */
    public record RowOutcome(int line, String djUsername, Long userId, String username,
                             RowStatus status, String message) {}

    public record Result(boolean dryRun, List<RowOutcome> rows, int total, int ok, int notFound,
                         int failed) {}

    public Result update(Long groupId, String text, boolean dryRun) {
        groupRepository.findById(groupId)
            .filter(g -> Boolean.TRUE.equals(g.getIsActive()))
            .orElseThrow(() -> ApiException.notFound("No such group"));

        List<RosterParser.Row> parsed;
        try {
            parsed = RosterParser.parse(text);
        } catch (RosterParser.RosterFormatException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (parsed.isEmpty()) throw ApiException.badRequest("There are no rows in that paste.");

        // Who in this group has which login attached, and who is named what. Both keyed
        // case-insensitively: a spreadsheet is not a reliable place to preserve case.
        Map<String, GroupMember> byAttachedLogin = new HashMap<>();
        Map<String, GroupMember> byUsername = new HashMap<>();
        for (GroupMember member : memberRepository.findByGroup(groupId)) {
            Long userId = member.getUser().getUserId();
            DomjudgeCredentialStore.Stored stored = credentials.find(userId);
            if (stored != null) byAttachedLogin.put(key(stored.username()), member);
            byUsername.put(key(member.getUser().getUsername()), member);
        }

        String unavailable = domjudgeAccounts.unavailableReason();
        Set<String> seen = new HashSet<>();
        List<RowOutcome> out = new ArrayList<>(parsed.size());

        for (RosterParser.Row row : parsed) {
            String login = row.djUsername();
            if (login == null) {
                out.add(new RowOutcome(row.lineNumber(), null, null, null, RowStatus.FAILED,
                    "No djUsername on this row."));
                continue;
            }
            if (!seen.add(key(login))) {
                out.add(new RowOutcome(row.lineNumber(), login, null, null, RowStatus.DUPLICATE,
                    "Appears more than once in this paste; only the first is used."));
                continue;
            }

            GroupMember attached = byAttachedLogin.get(key(login));
            GroupMember named = attached == null ? byUsername.get(key(login)) : null;
            GroupMember member = attached != null ? attached : named;
            if (member == null) {
                out.add(new RowOutcome(row.lineNumber(), login, null, null, RowStatus.NOT_FOUND,
                    "No member of this group has this DOMjudge login attached."));
                continue;
            }

            Long userId = member.getUser().getUserId();
            String username = member.getUser().getUsername();

            if (row.djPassword() == null) {
                out.add(new RowOutcome(row.lineNumber(), login, userId, username,
                    RowStatus.FAILED, "No djPassword on this row."));
                continue;
            }
            if (unavailable != null) {
                out.add(new RowOutcome(row.lineNumber(), login, userId, username,
                    RowStatus.FAILED, unavailable));
                continue;
            }

            try {
                String team;
                RowStatus status;
                if (dryRun) {
                    team = domjudgeAccounts.verify(login, row.djPassword()).teamLabel();
                    status = attached != null ? RowStatus.WILL_CHANGE : RowStatus.WILL_ATTACH;
                } else if (attached != null) {
                    team = teamOf(domjudgeAccounts.changePassword(userId, row.djPassword()));
                    status = RowStatus.CHANGED;
                } else {
                    team = teamOf(domjudgeAccounts.provision(new DomjudgeDto.ProvisionRequest(
                        userId, login, row.djPassword(), null, null)));
                    status = RowStatus.ATTACHED;
                }
                out.add(new RowOutcome(row.lineNumber(), login, userId, username, status,
                    "Team " + team + "."));
            } catch (ApiException e) {
                out.add(new RowOutcome(row.lineNumber(), login, userId, username,
                    RowStatus.FAILED, e.getMessage()));
            } catch (RuntimeException e) {
                // Every later row would wait out the same timeout to learn the same thing.
                log.warn("DOMjudge did not answer during a password update: {}",
                    e.getClass().getSimpleName());
                unavailable = "DOMjudge did not answer, so this password could not be checked. "
                    + "Try again once the judge is reachable.";
                out.add(new RowOutcome(row.lineNumber(), login, userId, username,
                    RowStatus.FAILED, unavailable));
            }
        }

        if (!dryRun) {
            log.info("DOMjudge passwords updated for group {}: {} rows", groupId, out.size());
        }
        return summarise(dryRun, out);
    }

    private static String teamOf(DomjudgeDto.AccountStatus status) {
        return status.teamName() != null ? status.teamName() : status.teamId();
    }

    private static String key(String login) {
        return login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
    }

    private static Result summarise(boolean dryRun, List<RowOutcome> rows) {
        int ok = 0, notFound = 0, failed = 0;
        for (RowOutcome r : rows) {
            switch (r.status()) {
                case WILL_CHANGE, WILL_ATTACH, CHANGED, ATTACHED -> ok++;
                case NOT_FOUND -> notFound++;
                case FAILED -> failed++;
                default -> { }
            }
        }
        return new Result(dryRun, rows, rows.size(), ok, notFound, failed);
    }
}
