package com.cpintel.integration.domjudge;

import com.cpintel.classrooms.ClassroomService;
import com.cpintel.entity.Classroom;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Attaching a DOMjudge account to a CPIntel one, and reading what that account can see.
 *
 * <p><b>An admin attaches, a contestant competes.</b> That split is the whole design. The
 * people sitting a round do not type a DOMjudge password into CPIntel — somebody running the
 * contest provisions each account once, and from then on the contestant signs into CPIntel as
 * usual and the arena acts as them on the judge. It keeps the credential out of the hands of
 * the person most likely to paste it somewhere, and it means a round can be set up before the
 * contestants ever log in.
 *
 * <p><b>Verification is not optional.</b> {@link #provision} calls the judge before storing
 * anything, and rejects an account with no team. A password that authenticates but belongs to
 * a jury or admin login would be accepted by every other endpoint and then attribute the
 * contestant's submissions to nobody — a failure that surfaces only when somebody's solved
 * problems are missing from the board at the end of a contest. Failing at provisioning time
 * turns that into a message on the admin's screen while there is still time to fix it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DomjudgeAccountService {

    private final DomjudgeJudges judges;
    private final ClassroomService classrooms;
    private final DomjudgeCredentialStore credentials;
    private final UserRepository userRepository;

    // ------------------------------------------------------------ provisioning

    /**
     * Attaches a DOMjudge login to a CPIntel account, after proving it works.
     *
     * @return the resulting status, which names the team so the admin can check it is the
     *         right one before the round rather than after it
     */
    public DomjudgeDto.AccountStatus provision(Long classroomId, DomjudgeDto.ProvisionRequest req) {
        String unavailable = unavailableReason(classroomId);
        if (unavailable != null) throw ApiException.badRequest(unavailable);
        if (!userRepository.existsById(req.userId())) {
            throw ApiException.notFound("No CPIntel user with id " + req.userId() + ".");
        }

        Verified verified = verify(classroomId, req.username(), req.password());
        DomjudgeCredentialStore.Stored candidate = verified.candidate();
        DjModels.User account = verified.account();
        String judgeTeamId = verified.teamId();
        String judgeTeamName = verified.teamName();

        String assignedId = StringUtils.hasText(req.teamId()) ? req.teamId().trim() : null;
        String assignedName = assignedId == null ? null
            : teamNameById(judges.forClassroom(classroomId), candidate, assignedId);

        DomjudgeCredentialStore.Stored stored = new DomjudgeCredentialStore.Stored(
            req.username().trim(), req.password(),
            StringUtils.hasText(req.name()) ? req.name().trim() : nameOf(account),
            judgeTeamId, judgeTeamName != null ? judgeTeamName : teamNameOf(account),
            assignedId, assignedName,
            Instant.now());

        credentials.save(classroomId, req.userId(), stored);
        classrooms.recordLogin(classroomId, req.userId(), stored.username());
        if (stored.teamMismatch()) {
            // Logged rather than refused: an admin may genuinely want somebody grouped apart
            // from the team the judge has them on, and only they can tell.
            log.info("DOMjudge account {} is on team {} but was assigned to {} for CPIntel user "
                + "{}", stored.username(), stored.teamId(), assignedId, req.userId());
        } else {
            log.info("Attached DOMjudge account {} (team {}) to CPIntel user {}",
                stored.username(), stored.teamId(), req.userId());
        }

        return status(classroomId, req.userId());
    }

    /**
     * Why no account can be attached on this deployment right now, or null when one can.
     *
     * Asked once up front by a bulk import, so that a missing setting is one message on the
     * screen rather than the same failure repeated on every row.
     */
    public String unavailableReason(Long classroomId) {
        if (!judges.forClassroom(classroomId).isConfigured()) {
            return "This classroom has no DOMjudge URL set, so there is nothing to attach an "
                + "account to. Set it on the classroom's page first.";
        }
        if (!credentials.isConfigured()) {
            return "CPINTEL_DOMJUDGE_CREDENTIAL_KEY is not set, so DOMjudge credentials cannot be "
                + "stored. Set it and restart before attaching accounts.";
        }
        return null;
    }

    /** A login the judge accepted, and the team it files submissions under. */
    public record Verified(DomjudgeCredentialStore.Stored candidate, DjModels.User account,
                           String teamId, String teamName) {

        /** The team's name where the judge gave one, else its id — what a roster calls it. */
        public String teamLabel() {
            return teamName != null ? teamName : teamId;
        }
    }

    /**
     * Proves a login works and belongs to a team, without storing anything.
     *
     * Separate from {@link #provision} so a bulk import can check every row in its preview,
     * before any CPIntel account has been created for the person.
     *
     * @throws ApiException when the judge refuses the login or it has no team
     */
    public Verified verify(Long classroomId, String username, String password) {
        DomjudgeClient domjudge = judges.forClassroom(classroomId);
        // The candidate is built only so the client has something to authenticate with; it is
        // not stored unless the caller goes on to provision.
        DomjudgeCredentialStore.Stored candidate = new DomjudgeCredentialStore.Stored(
            username.trim(), password, null, null, null, null, null, Instant.now());

        DjModels.User account = domjudge.whoami(candidate);
        if (account == null) {
            throw ApiException.badRequest(
                "DOMjudge accepted those credentials but said nothing about the account.");
        }

        // Which of the two fields carries the team depends on the judge's version: 8.2 and
        // later report team_id, 8.0 reports only the name. Asking for the id alone made every
        // team account on an 8.0 instance read as an admin one, so the name is enough to pass
        // and the id is resolved from it where the judge will say.
        String judgeTeamId = StringUtils.hasText(account.getTeam_id())
            ? account.getTeam_id().trim() : null;
        String judgeTeamName = StringUtils.hasText(account.getTeam())
            ? account.getTeam().trim() : null;

        if (judgeTeamId == null && judgeTeamName != null) {
            judgeTeamId = teamIdByName(domjudge, candidate, judgeTeamName);
        }

        // Still required even when the admin names a team of their own. The admin's choice
        // governs how CPIntel groups this person; it cannot govern where the judge files their
        // code, because the judge reads that off the login. An account with no team would have
        // its submissions accepted and then attributed to nobody, and no amount of assigning
        // here would change that.
        if (judgeTeamId == null && judgeTeamName == null) {
            throw ApiException.badRequest(
                "The DOMjudge account '" + username.trim() + "' is not attached to a "
                    + "team, so submissions made as it would not land on any scoreboard. "
                    + "Attach a team account rather than an admin or jury one.");
        }
        return new Verified(candidate, account, judgeTeamId, judgeTeamName);
    }

    /**
     * The teams an admin may choose from.
     *
     * Read with the service account where there is one, so the picker can be populated before
     * anybody types a password. An empty list is a normal answer on a build that will not show
     * teams to this account, and the screen falls back to letting the judge decide.
     */
    public List<DomjudgeDto.TeamOption> teams(Long classroomId) {
        DomjudgeClient domjudge = judges.forClassroom(classroomId);
        if (!domjudge.isConfigured()) return List.of();

        List<DjModels.Team> teams = domjudge.getAllTeams(null);
        if (teams == null) return List.of();

        List<DomjudgeDto.TeamOption> out = new ArrayList<>(teams.size());
        for (DjModels.Team team : teams) {
            if (team.getId() == null) continue;
            out.add(new DomjudgeDto.TeamOption(team.getId(), displayNameOf(team)));
        }
        out.sort(Comparator.comparing(DomjudgeDto.TeamOption::name,
            String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /**
     * The id of a team the judge named but did not number.
     *
     * DOMjudge 8.0 has no instance-wide team list and its {@code /user} gives only the team's
     * name, so the id is found by matching that name against the teams of the contests this
     * account can see. A team registered for no contest yet is not findable — which is not a
     * failure worth refusing over: the name alone still identifies them on a scoreboard, and
     * the id appears the moment an admin registers the team for the round.
     */
    private String teamIdByName(DomjudgeClient domjudge, DomjudgeCredentialStore.Stored as,
                                String teamName) {
        List<DjModels.Team> teams = domjudge.getAllTeams(
            domjudge.hasServiceAccount() ? null : as);
        if (teams == null) return null;

        for (DjModels.Team team : teams) {
            if (teamName.equalsIgnoreCase(team.getName())
                || teamName.equalsIgnoreCase(team.getDisplay_name())) {
                return team.getId();
            }
        }
        log.debug("DOMjudge named team '{}' but no contest this account can see lists it",
            teamName);
        return null;
    }

    /** A chosen team's name, looked up with whatever credentials are to hand. */
    private String teamNameById(DomjudgeClient domjudge, DomjudgeCredentialStore.Stored as,
                                String teamId) {
        List<DjModels.Team> teams = domjudge.getAllTeams(
            domjudge.hasServiceAccount() ? null : as);
        if (teams == null) return null;

        for (DjModels.Team team : teams) {
            if (teamId.equals(team.getId())) return displayNameOf(team);
        }
        // The id is kept even when it cannot be named — an admin who typed it may know
        // something this account cannot see, and refusing would be the less useful answer.
        return null;
    }

    private String displayNameOf(DjModels.Team team) {
        return StringUtils.hasText(team.getDisplay_name())
            ? team.getDisplay_name() : team.getName();
    }

    private String nameOf(DjModels.User account) {
        return StringUtils.hasText(account.getName())
            ? account.getName() : account.getUsername();
    }

    /**
     * A last-resort label for the team, used only when the judge named no team at all.
     *
     * Every version that reports a team reports its name, so this is reached only on a build
     * that carries the id and nothing else. It is a label on a confirmation screen rather than
     * anything the code matches on, so the account's own name is survivable here in a way a
     * wrong team id would not be.
     */
    private String teamNameOf(DjModels.User account) {
        return StringUtils.hasText(account.getName())
            ? account.getName() : account.getUsername();
    }

    /**
     * Replaces the stored password after the login's password was changed on the judge.
     *
     * <p>Everything else stays as it was: the login, the name and the admin's team choice. Only
     * the judge's own team is refreshed, since the judge is the one authority on it and a
     * password change is a natural moment for an admin to have moved the account too. The
     * new password is verified before it replaces the old one, so a typo leaves the working
     * credentials in place rather than breaking them. Saving also restarts the expiry clock.
     */
    public DomjudgeDto.AccountStatus changePassword(Long classroomId, Long userId,
                                                    String newPassword) {
        String unavailable = unavailableReason(classroomId);
        if (unavailable != null) throw ApiException.badRequest(unavailable);

        DomjudgeCredentialStore.Stored current = credentials.find(classroomId, userId);
        if (current == null) {
            throw ApiException.badRequest(
                "No DOMjudge account is attached to this user, so there is no password to "
                    + "change. Attach one instead.");
        }

        Verified verified = verify(classroomId, current.username(), newPassword);
        String teamName = verified.teamName() != null ? verified.teamName() : current.teamName();

        DomjudgeCredentialStore.Stored updated = new DomjudgeCredentialStore.Stored(
            current.username(), newPassword, current.name(),
            verified.teamId(), teamName,
            current.assignedTeamId(), current.assignedTeamName(),
            Instant.now());
        credentials.save(classroomId, userId, updated);

        if (!java.util.Objects.equals(current.teamId(), updated.teamId())) {
            log.info("DOMjudge account {} moved from team {} to {} while changing its password",
                current.username(), current.teamId(), updated.teamId());
        }
        log.info("Changed the DOMjudge password stored for CPIntel user {} (dj user={})",
            userId, current.username());
        return status(classroomId, userId);
    }

    public void revoke(Long classroomId, Long userId) {
        credentials.delete(classroomId, userId);
        classrooms.recordLogin(classroomId, userId, null);
        log.info("Removed the DOMjudge account attached to CPIntel user {} in classroom {}",
            userId, classroomId);
    }

    public DomjudgeDto.AccountStatus status(Long classroomId, Long userId) {
        DomjudgeCredentialStore.Stored stored = credentials.find(classroomId, userId);
        if (stored == null) return DomjudgeDto.AccountStatus.unlinked();

        return new DomjudgeDto.AccountStatus(
            true, stored.username(), stored.name(),
            stored.teamId(), stored.teamName(),
            stored.assignedTeamId(), stored.assignedTeamName(), stored.teamMismatch(),
            stored.provisioned());
    }

    // ------------------------------------------------------------ contest list

    /**
     * The contests this contestant may enter, newest first.
     *
     * Read as them, which is what makes the picker meaningful — DOMjudge already knows which
     * contests a team is registered for, so the list needs no roster of CPIntel's own. A
     * finished contest is kept rather than filtered: someone opening the page after a round
     * wants to see where they came, and the arena renders a finished contest read-only.
     */
    public List<DomjudgeDto.ContestSummary> contests(Long userId) {
        Instant now = Instant.now();
        List<DomjudgeDto.ContestSummary> out = new ArrayList<>();
        boolean anyLogin = false;

        // Every classroom the student has a login in, each asked as that login. One judge
        // being down costs its own rows, not the whole picker.
        for (Classroom classroom : classrooms.judgeClassroomsOf(userId)) {
            Long classroomId = classroom.getClassroomId();
            DomjudgeCredentialStore.Stored stored = credentials.find(classroomId, userId);
            if (stored == null) continue;
            anyLogin = true;

            List<DjModels.Contest> raw;
            try {
                raw = judges.forClassroom(classroomId).getContests(stored);
            } catch (Exception e) {
                log.warn("Could not list contests on classroom {}'s judge for user {}: {}",
                    classroomId, userId, e.getMessage());
                continue;
            }
            if (raw != null) collect(out, raw, classroom, now);
        }
        if (!anyLogin) {
            throw ApiException.forbidden("No DOMjudge account is attached to your CPIntel "
                + "account in any classroom, so there is nothing to compete as. Ask the admin "
                + "running your classroom to attach one.");
        }

        // Running first, then the soonest upcoming, then the most recently finished — the
        // order someone opening the picker mid-event actually wants.
        out.sort(Comparator
            .comparing((DomjudgeDto.ContestSummary c) -> !c.running())
            .thenComparing(c -> c.startsAt() == null ? Instant.EPOCH : c.startsAt(),
                Comparator.reverseOrder()));
        return out;
    }

    private void collect(List<DomjudgeDto.ContestSummary> out, List<DjModels.Contest> raw,
                         Classroom classroom, Instant now) {
        for (DjModels.Contest contest : raw) {
            if (contest.getId() == null) continue;

            Instant startsAt = DjTime.instant(contest.getStart_time());
            long duration = DjTime.seconds(contest.getDuration(), 0);

            Instant endsAt = DjTime.instant(contest.getEnd_time());
            if (endsAt == null && startsAt != null && duration > 0) {
                endsAt = startsAt.plusSeconds(duration);
            }

            // Derived from the clock rather than from /state, which would be one extra call
            // per contest in the list. The picker only needs to sort and label; the arena
            // re-reads the authoritative state the moment a contest is actually opened.
            boolean started = startsAt != null && !now.isBefore(startsAt);
            boolean ended = endsAt != null && !now.isBefore(endsAt);
            String phase = ended ? "FINISHED" : (started ? "CODING" : "BEFORE");

            out.add(new DomjudgeDto.ContestSummary(
                JudgeContestRef.encode(classroom.getClassroomId(), contest.getId()),
                contest.getId(),
                classroom.getClassroomId(),
                classroom.getName(),
                StringUtils.hasText(contest.getFormal_name()) ? contest.getFormal_name()
                    : (StringUtils.hasText(contest.getName()) ? contest.getName()
                        : "Contest " + contest.getId()),
                phase,
                started && !ended,
                startsAt,
                endsAt,
                duration,
                startsAt == null ? 0 : startsAt.getEpochSecond() - now.getEpochSecond()));
        }
    }
}
