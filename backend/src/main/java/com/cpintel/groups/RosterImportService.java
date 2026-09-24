package com.cpintel.groups;

import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.GroupMember;
import com.cpintel.entity.UnifiedScore;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.domjudge.DomjudgeAccountService;
import com.cpintel.integration.domjudge.DomjudgeDto;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.security.Roles;
import com.cpintel.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bulk roster import: paste a class list, get a populated group.
 *
 * <p>Adding a group's members was one click each, against a list of accounts that had to exist
 * already — and self-registration is off by default, so for a teacher with a class of two
 * hundred there was no path at all. This closes that: one paste creates whatever accounts are
 * missing, adds everyone to the group, and records the judge handle each member is found under.
 *
 * <h2>Preview before commit</h2>
 *
 * <p>Every import runs through the same code twice: once as a dry run that writes nothing and
 * reports what each row would do, and again to commit. A bad column heading in a two hundred row
 * paste is otherwise two hundred wrong accounts, and accounts cannot be un-created.
 *
 * <h2>Who may create accounts</h2>
 *
 * <p>Either console tier may run an import, including one that creates accounts. An admin
 * running a contest has to be able to add the people sitting it, and routing every new
 * participant through a super admin makes a class list an escalation request.
 *
 * <p>That is safe here for one specific reason, and it is a property of this code rather than
 * of the permission: every account created by an import is a {@code USER}, always, with no way
 * to say otherwise — see the {@code role(Roles.USER)} below. A spreadsheet column cannot hand
 * out console access, so a bulk import cannot be used to manufacture privilege, and the rule
 * that only a super admin assigns roles is untouched. If that line ever becomes settable from
 * a row, this permission has to move back.
 *
 * <h2>Teams</h2>
 *
 * <p>An import may name one team for everybody, which is what a class list normally wants. A
 * team named on a row still wins over it, so a mixed roster keeps its own assignments.
 *
 * <h2>DOMjudge logins</h2>
 *
 * <p>A row may carry a DOMjudge team login ({@code djUsername}, {@code djPassword}), which is
 * attached to the account exactly as the one-at-a-time admin screen would — verified against
 * the judge first, and refused if it has no team. The preview verifies too, so a wrong password
 * shows up before any account exists. A login that fails does not stop the row: the account is
 * still created and added, and the failure is reported beside it to fix on its own.
 *
 * <p>A new account takes the DOMjudge username as its CPIntel username when the row names no
 * username of its own, so a contestant has one name to remember. A row with only a DOMjudge
 * login and no email or username finds the account of that name, which is how a round's logins
 * are re-attached after the stored ones expire.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RosterImportService {

    private final ContestGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final UnifiedScoreRepository unifiedScoreRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final DomjudgeAccountService domjudgeAccounts;

    /** Matches AdminDto.CreateUserRequest, so a bulk account is never weaker than a typed one. */
    private static final Pattern USERNAME_OK = Pattern.compile("^[a-zA-Z0-9_]+$");
    private static final Pattern EMAIL_OK =
        Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final int USERNAME_MIN = 3;
    private static final int USERNAME_MAX = 50;

    /** Generated password length. Comfortably past the 8-character floor the API enforces. */
    private static final int PASSWORD_LENGTH = 14;

    /**
     * Characters generated passwords are drawn from.
     *
     * <p>No {@code O}/{@code 0} or {@code l}/{@code 1}: these get printed on a handout and typed
     * back in by a room full of people, and an ambiguous glyph turns into a support queue.
     */
    private static final String PASSWORD_ALPHABET =
        "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();

    /** What will happen, or did happen, to one row. */
    public enum RowStatus {
        /** Matched an existing account that is not yet in the group. */
        ADD_EXISTING,
        /** No account with this identity; one would be created. */
        CREATE_AND_ADD,
        /** Matched an account that is already a member; the handle is refreshed if given. */
        ALREADY_MEMBER,
        /** The same person appears earlier in the paste. */
        DUPLICATE,
        /** Unusable: missing identity, malformed email, or an unusable username. */
        INVALID
    }

    /** What will happen, or did happen, to the DOMjudge login on one row. */
    public enum DomjudgeStatus {
        /** The row carries no DOMjudge login. */
        NONE,
        /** The judge accepted the login; it will be attached on import. */
        VERIFIED,
        /** Attached to the account. */
        ATTACHED,
        /** Not attached; the message says why. The rest of the row is unaffected. */
        FAILED
    }

    /**
     * Plan or outcome for one row.
     *
     * <p>{@code generatedPassword} is populated only on a committed creation, and only in the
     * response to the request that created it. It is never stored in readable form and there is
     * no endpoint that will hand it back later — losing it means resetting the password, which
     * is the correct trade for not keeping a list of live credentials on the server.
     */
    public record RowOutcome(
        int line,
        String email,
        String username,
        String fullName,
        String cfHandle,
        String teamName,
        RowStatus status,
        String message,
        Long userId,
        String generatedPassword,
        /** The DOMjudge login named on the row. Its password is never echoed back. */
        String djUsername,
        DomjudgeStatus domjudgeStatus,
        /** The judge's team on success, or why the login was not attached. */
        String domjudgeMessage
    ) {
        RowOutcome withDomjudge(DomjudgeStatus status, String message) {
            return new RowOutcome(line, email, username, fullName, cfHandle, teamName, this.status,
                this.message, userId, generatedPassword, djUsername, status, message);
        }
    }

    public record ImportResult(
        boolean dryRun,
        List<RowOutcome> rows,
        int total,
        int toAdd,
        int toCreate,
        int alreadyMembers,
        int duplicates,
        int invalid,
        /** Rows whose DOMjudge login verified (preview) or was attached (import). */
        int domjudgeOk,
        /** Rows whose DOMjudge login could not be attached. */
        int domjudgeFailed,
        /**
         * True when the caller may create the accounts this import needs.
         *
         * Always true as things stand — both console tiers may, because every account an
         * import creates is an ordinary USER. Kept because it is the screen's answer to
         * "offer the create step or not", and that should not become a role check in the UI.
         */
        boolean mayCreateAccounts,
        /** Set when the import cannot proceed as a whole, explaining why. */
        String blockedReason
    ) {}

    /**
     * Work out what a paste would do, or do it.
     *
     * @param dryRun             true to write nothing and only report the plan
     * @param callerIsSuperAdmin unused for permission — both console tiers may run any import,
     *                           because every account created here is an ordinary USER. Kept
     *                           on the signature so that reinstating a tier rule is a change
     *                           to this method rather than to every caller.
     * @param defaultTeam        the team to put rows on when they name none, or null
     */
    @Transactional
    public ImportResult importRoster(Long adminId, Long groupId, String text,
                                     boolean dryRun, boolean callerIsSuperAdmin,
                                     String defaultTeam, HttpServletRequest httpReq) {

        ContestGroup group = groupRepository.findById(groupId)
            .filter(g -> Boolean.TRUE.equals(g.getIsActive()))
            .orElseThrow(() -> ApiException.notFound("No such group"));

        List<RosterParser.Row> parsed;
        try {
            parsed = RosterParser.parse(text);
        } catch (RosterParser.RosterFormatException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (parsed.isEmpty()) {
            throw ApiException.badRequest("There are no rows in that paste.");
        }

        // Names already claimed, including by rows earlier in this same paste — two rows both
        // deriving "asha" from different addresses must not collide at insert time.
        Set<String> plannedUsernames = new HashSet<>();
        Set<String> seenIdentities = new LinkedHashSet<>();

        String fallbackTeam = StringUtils.hasText(defaultTeam) ? defaultTeam.trim() : null;

        List<RosterParser.Row> rows = parsed.stream().map(r -> withTeam(r, fallbackTeam)).toList();
        List<RowOutcome> outcomes = new ArrayList<>(rows.size());

        for (RosterParser.Row row : rows) {
            outcomes.add(plan(group, row, plannedUsernames, seenIdentities));
        }

        int toCreate = (int) outcomes.stream()
            .filter(o -> o.status() == RowStatus.CREATE_AND_ADD).count();

        // Nothing here is refused by tier any more: every account an import creates is a
        // USER, so there is no privilege for the higher tier to be guarding.
        DomjudgeCheck judge = new DomjudgeCheck(domjudgeAccounts.unavailableReason());

        if (dryRun) {
            List<RowOutcome> checked = new ArrayList<>(outcomes.size());
            for (int i = 0; i < outcomes.size(); i++) {
                checked.add(previewDomjudge(outcomes.get(i), rows.get(i), judge));
            }
            return summarise(checked, true, null);
        }

        List<RowOutcome> committed = new ArrayList<>(outcomes.size());
        for (int i = 0; i < outcomes.size(); i++) {
            committed.add(commit(group, outcomes.get(i), rows.get(i), judge, adminId, httpReq));
        }

        auditService.record(adminId, AuditService.GROUP_MEMBER_ADDED, "GROUP",
            groupId + ":bulk:" + committed.size(), httpReq);
        log.info("Admin {} bulk-imported {} rows into group {} ({} accounts created)",
            adminId, committed.size(), groupId, toCreate);

        return summarise(committed, false, null);
    }

    /**
     * The row, with the import's team filled in where it named none.
     *
     * Per-row wins on purpose. The import-wide team is a convenience for the common roster
     * that has no team column at all; where somebody has gone to the trouble of naming teams
     * per person, silently overwriting them would be the more surprising behaviour.
     */
    private RosterParser.Row withTeam(RosterParser.Row row, String fallbackTeam) {
        if (fallbackTeam == null || StringUtils.hasText(row.teamName())) return row;
        return new RosterParser.Row(row.lineNumber(), row.email(), row.username(),
            row.fullName(), row.cfHandle(), fallbackTeam, row.djUsername(), row.djPassword());
    }

    // -- planning ----------------------------------------------------------

    private RowOutcome plan(ContestGroup group, RosterParser.Row row,
                            Set<String> plannedUsernames, Set<String> seenIdentities) {

        String email = row.email() == null ? null : row.email().trim().toLowerCase(Locale.ROOT);
        // With nothing else to go on, the DOMjudge login names the account: new accounts are
        // created under it, so on a re-import it is the name the account already has.
        String username = row.username() != null ? row.username().trim()
            : email == null ? row.djUsername() : null;

        if (email == null && username == null) {
            return invalid(row, "No email or username on this row.");
        }
        if (email != null && !EMAIL_OK.matcher(email).matches()) {
            return invalid(row, "'" + email + "' is not a usable email address.");
        }

        String identity = email != null ? "e:" + email : "u:" + username.toLowerCase(Locale.ROOT);
        if (!seenIdentities.add(identity)) {
            return outcome(row, RowStatus.DUPLICATE,
                "Appears more than once in this paste; only the first is used.", null, null);
        }

        Optional<User> existing = Optional.<User>empty()
            .or(() -> email == null ? Optional.empty() : userRepository.findByEmail(email))
            .or(() -> username == null ? Optional.empty() : userRepository.findByUsername(username));

        if (existing.isPresent()) {
            User user = existing.get();
            boolean already = memberRepository
                .existsByGroupGroupIdAndUserUserId(group.getGroupId(), user.getUserId());
            return outcome(row,
                already ? RowStatus.ALREADY_MEMBER : RowStatus.ADD_EXISTING,
                already
                    ? "Already in this group" + (row.teamName() != null ? "; handle updated." : ".")
                    : "Existing account " + user.getUsername() + ".",
                user.getUserId(), null);
        }

        // Nothing matched, so this row means a new account. It needs a username whether or not
        // the paste supplied one; the DOMjudge login is preferred to the email so a contestant
        // has one name for both.
        String candidate = username != null ? username
            : row.djUsername() != null ? row.djUsername()
            : usernameFromEmail(email);
        String resolved = uniqueUsername(candidate, plannedUsernames);
        if (resolved == null) {
            return invalid(row, "Could not make a valid username from '"
                + (username != null ? username : email) + "'.");
        }
        plannedUsernames.add(resolved.toLowerCase(Locale.ROOT));

        if (email == null) {
            return invalid(row,
                "New accounts need an email address; '" + username + "' matched nobody.");
        }

        String note = row.djUsername() != null && username == null
            && !resolved.equals(row.djUsername())
            ? " As " + resolved + ", since '" + row.djUsername() + "' is taken or not a valid "
                + "username."
            : "";
        return new RowOutcome(row.lineNumber(), email, resolved, row.fullName(),
            row.cfHandle(), row.teamName(), RowStatus.CREATE_AND_ADD,
            "New account will be created." + note, null, null,
            row.djUsername(), DomjudgeStatus.NONE, null);
    }

    // -- DOMjudge logins ---------------------------------------------------

    /**
     * Whether the judge can be asked at all, carried across the rows of one import.
     *
     * <p>Once the judge has failed to answer, every later row would wait out the same timeout
     * to learn the same thing, so the first such failure is remembered and the rest are
     * reported without asking.
     */
    private static final class DomjudgeCheck {
        String unavailable;

        DomjudgeCheck(String unavailable) { this.unavailable = unavailable; }
    }

    /** Why a row's login cannot even be tried, or null when it can. */
    private String loginProblem(RowOutcome planned, RosterParser.Row row, DomjudgeCheck judge) {
        if (row.djPassword() == null) {
            return "The DOMjudge login '" + row.djUsername() + "' has no password on this row.";
        }
        if (planned.status() == RowStatus.INVALID || planned.status() == RowStatus.DUPLICATE) {
            return "Not attached, because the row itself is skipped.";
        }
        return judge.unavailable;
    }

    private RowOutcome previewDomjudge(RowOutcome planned, RosterParser.Row row,
                                       DomjudgeCheck judge) {
        if (row.djUsername() == null) {
            return row.djPassword() == null ? planned : planned.withDomjudge(
                DomjudgeStatus.FAILED, "A DOMjudge password with no djUsername beside it.");
        }
        String problem = loginProblem(planned, row, judge);
        if (problem != null) return planned.withDomjudge(DomjudgeStatus.FAILED, problem);

        try {
            DomjudgeAccountService.Verified verified =
                domjudgeAccounts.verify(row.djUsername(), row.djPassword());
            return planned.withDomjudge(DomjudgeStatus.VERIFIED,
                "Team " + verified.teamLabel() + ".");
        } catch (ApiException e) {
            return planned.withDomjudge(DomjudgeStatus.FAILED, e.getMessage());
        } catch (RuntimeException e) {
            return planned.withDomjudge(DomjudgeStatus.FAILED, judgeDown(judge, e));
        }
    }

    /**
     * Attaches the row's login to the account.
     *
     * @return the outcome with its DOMjudge fields filled, and the judge's team name when the
     *         login was attached
     */
    private Attached attach(RowOutcome row, RosterParser.Row source, Long userId,
                            DomjudgeCheck judge) {
        if (source.djUsername() == null) {
            return new Attached(source.djPassword() == null ? row : row.withDomjudge(
                DomjudgeStatus.FAILED, "A DOMjudge password with no djUsername beside it."), null);
        }
        String problem = loginProblem(row, source, judge);
        if (problem != null) {
            return new Attached(row.withDomjudge(DomjudgeStatus.FAILED, problem), null);
        }

        try {
            DomjudgeDto.AccountStatus status = domjudgeAccounts.provision(
                new DomjudgeDto.ProvisionRequest(userId, source.djUsername(), source.djPassword(),
                    source.fullName(), null));
            String team = status.teamName() != null ? status.teamName() : status.teamId();
            return new Attached(
                row.withDomjudge(DomjudgeStatus.ATTACHED, "Team " + team + "."), team);
        } catch (ApiException e) {
            return new Attached(row.withDomjudge(DomjudgeStatus.FAILED, e.getMessage()), null);
        } catch (RuntimeException e) {
            return new Attached(
                row.withDomjudge(DomjudgeStatus.FAILED, judgeDown(judge, e)), null);
        }
    }

    private record Attached(RowOutcome row, String teamName) {}

    private String judgeDown(DomjudgeCheck judge, RuntimeException e) {
        log.warn("DOMjudge did not answer during a roster import: {}", e.getClass().getSimpleName());
        judge.unavailable = "DOMjudge did not answer, so this login could not be checked. "
            + "Try again once the judge is reachable.";
        return judge.unavailable;
    }

    // -- committing --------------------------------------------------------

    /**
     * Carries out one planned row.
     *
     * <p>The DOMjudge login is attached before the member is added, because the team the judge
     * reports is the handle the member is scored under when the row named no team of its own.
     * A roster of logins alone therefore still produces a working standings board.
     */
    private RowOutcome commit(ContestGroup group, RowOutcome planned, RosterParser.Row source,
                              DomjudgeCheck judge, Long adminId, HttpServletRequest httpReq) {
        switch (planned.status()) {
            case INVALID, DUPLICATE -> {
                return attach(planned, source, null, judge).row();
            }
            case ALREADY_MEMBER -> {
                Attached attached = attach(planned, source, planned.userId(), judge);
                // The one thing worth doing for an existing member: refresh the judge handle,
                // which is the field most likely to be the reason for re-importing at all.
                String handle = handleFor(planned, attached);
                if (handle != null) {
                    memberRepository
                        .findByGroupGroupIdAndUserUserId(group.getGroupId(), planned.userId())
                        .ifPresent(m -> {
                            m.setExternalHandle(handle);
                            memberRepository.save(m);
                        });
                }
                return attached.row();
            }
            case ADD_EXISTING -> {
                Attached attached = attach(planned, source, planned.userId(), judge);
                User user = userRepository.getReferenceById(planned.userId());
                addMember(group, user, handleFor(planned, attached));
                return attached.row();
            }
            case CREATE_AND_ADD -> {
                String password = generatePassword();
                User user = createAccount(planned, password);

                auditService.record(adminId, AuditService.USER_CREATED, "USER",
                    user.getUserId() + ":" + Roles.USER + ":bulk", httpReq);

                RowOutcome created = new RowOutcome(planned.line(), planned.email(),
                    user.getUsername(), planned.fullName(), planned.cfHandle(),
                    planned.teamName(), RowStatus.CREATE_AND_ADD, "Account created and added.",
                    user.getUserId(), password, planned.djUsername(), DomjudgeStatus.NONE, null);

                Attached attached = attach(created, source, user.getUserId(), judge);
                addMember(group, user, handleFor(planned, attached));
                return attached.row();
            }
            default -> {
                return planned;
            }
        }
    }

    /** The row's own team, else the one the judge reported for the attached login. */
    private String handleFor(RowOutcome planned, Attached attached) {
        return planned.teamName() != null ? planned.teamName() : attached.teamName();
    }

    private User createAccount(RowOutcome row, String password) {
        User user = userRepository.save(User.builder()
            .username(row.username())
            .email(row.email())
            .passwordHash(passwordEncoder.encode(password))
            .fullName(row.fullName())
            // Always USER. Roles are assigned deliberately, one at a time, by a super admin;
            // a spreadsheet column is not the place to hand out console access.
            .role(Roles.USER)
            .isActive(true)
            // An admin typing an address is not evidence it belongs to the person, exactly as
            // for a singly-created account.
            .isVerified(false)
            .build());

        // Mirrors registration and single creation: without this row there is no unified score
        // to write into.
        unifiedScoreRepository.save(UnifiedScore.builder().user(user).build());
        return user;
    }

    private void addMember(ContestGroup group, User user, String externalHandle) {
        memberRepository.save(GroupMember.builder()
            .group(group)
            .user(user)
            .externalHandle(externalHandle)
            .joinedAt(Instant.now())
            .build());
    }

    // -- helpers -----------------------------------------------------------

    /** The local part of an address, reduced to what the username rules allow. */
    private String usernameFromEmail(String email) {
        String local = email.substring(0, email.indexOf('@'));
        return local.replaceAll("[^a-zA-Z0-9_]", "");
    }

    /**
     * A username nobody holds, neither in the database nor earlier in this paste.
     *
     * <p>Returns null when the base cannot be made valid at all — an address whose local part is
     * entirely punctuation, say.
     */
    private String uniqueUsername(String base, Set<String> plannedUsernames) {
        String cleaned = base == null ? "" : base.replaceAll("[^a-zA-Z0-9_]", "");
        if (cleaned.isEmpty()) return null;
        if (cleaned.length() > USERNAME_MAX) cleaned = cleaned.substring(0, USERNAME_MAX);
        while (cleaned.length() < USERNAME_MIN) cleaned = cleaned + "0";
        if (!USERNAME_OK.matcher(cleaned).matches()) return null;

        if (isFree(cleaned, plannedUsernames)) return cleaned;

        // Suffix until something is free. Bounded so a pathological input cannot spin.
        String stem = cleaned.length() > USERNAME_MAX - 4
            ? cleaned.substring(0, USERNAME_MAX - 4) : cleaned;
        for (int n = 2; n < 1000; n++) {
            String candidate = stem + n;
            if (isFree(candidate, plannedUsernames)) return candidate;
        }
        return null;
    }

    private boolean isFree(String username, Set<String> plannedUsernames) {
        return !plannedUsernames.contains(username.toLowerCase(Locale.ROOT))
            && !userRepository.existsByUsername(username);
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private RowOutcome invalid(RosterParser.Row row, String message) {
        return outcome(row, RowStatus.INVALID, message, null, null);
    }

    private RowOutcome outcome(RosterParser.Row row, RowStatus status, String message,
                               Long userId, String password) {
        return new RowOutcome(row.lineNumber(), row.email(), row.username(), row.fullName(),
            row.cfHandle(), row.teamName(), status, message, userId, password,
            row.djUsername(), DomjudgeStatus.NONE, null);
    }

    private ImportResult summarise(List<RowOutcome> rows, boolean dryRun, String blocked) {
        return new ImportResult(
            dryRun,
            rows,
            rows.size(),
            (int) rows.stream().filter(r -> r.status() == RowStatus.ADD_EXISTING).count(),
            (int) rows.stream().filter(r -> r.status() == RowStatus.CREATE_AND_ADD).count(),
            (int) rows.stream().filter(r -> r.status() == RowStatus.ALREADY_MEMBER).count(),
            (int) rows.stream().filter(r -> r.status() == RowStatus.DUPLICATE).count(),
            (int) rows.stream().filter(r -> r.status() == RowStatus.INVALID).count(),
            (int) rows.stream().filter(r -> r.domjudgeStatus() == DomjudgeStatus.VERIFIED
                || r.domjudgeStatus() == DomjudgeStatus.ATTACHED).count(),
            (int) rows.stream().filter(r -> r.domjudgeStatus() == DomjudgeStatus.FAILED).count(),
            // Always true now that both console tiers may create. Kept in the response so the
            // screen need not know the caller's role to decide what to offer.
            true,
            blocked);
    }
}
