package com.cpintel.classrooms;

import com.cpintel.entity.Classroom;
import com.cpintel.entity.ClassroomMember;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import com.cpintel.integration.domjudge.DomjudgeJudges;
import com.cpintel.repository.jpa.ClassroomMemberRepository;
import com.cpintel.repository.jpa.ClassroomRepository;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.security.Roles;
import com.cpintel.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Classrooms: who runs them, who is enrolled, and which judge each one owns.
 *
 * <p><b>Who may do what.</b> Only a superadmin creates, archives and staffs classrooms, and a
 * superadmin runs all of them. Any number of admins can be added to a classroom; each runs the
 * ones they were added to, and cannot open any other by id ({@link ClassroomAccessInterceptor}). Students see the
 * classrooms they are enrolled in and nothing else — including on the judge, because the arena
 * refuses a classroom's contests to anyone not enrolled in it ({@code ExamSessionGuard}).
 *
 * <p><b>Enrolment is implied by everything else.</b> Adding someone to a team, importing a
 * roster or attaching a judge login all enrol them in the classroom first, so an admin never
 * has to do it as a separate step and a team can never hold someone its classroom does not.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassroomService {

    private final ClassroomRepository classrooms;
    private final ClassroomMemberRepository members;
    private final ContestGroupRepository groups;
    private final GroupContestRepository events;
    private final GroupMemberRepository groupMembers;
    private final UserRepository users;
    private final DomjudgeCredentialStore credentials;
    private final DomjudgeJudges judges;
    private final AuditService auditService;

    // ------------------------------------------------------------------ access

    public Classroom require(Long classroomId) {
        if (classroomId == null) throw ApiException.badRequest("Pick a classroom.");
        return classrooms.findById(classroomId)
            .orElseThrow(() -> ApiException.notFound("No classroom with id " + classroomId + "."));
    }

    /** Whether this admin runs the classroom. Superadmins run every one. */
    public boolean canManage(Long adminId, Classroom classroom) {
        if (isSuperAdmin()) return true;
        if (adminId == null) return false;
        return adminId.equals(classroom.getOwnerId())
            || classrooms.isStaff(classroom.getClassroomId(), adminId);
    }

    public Classroom requireManaged(Long adminId, Long classroomId) {
        Classroom classroom = require(classroomId);
        if (!canManage(adminId, classroom)) {
            throw ApiException.forbidden("You do not run the classroom \""
                + classroom.getName() + "\". A superadmin can add you to it.");
        }
        return classroom;
    }

    /** Ids of the classrooms this admin runs, or null for a superadmin (meaning all). */
    public List<Long> managedIds(Long adminId) {
        if (isSuperAdmin()) return null;
        return classrooms.findManagedBy(adminId).stream().map(Classroom::getClassroomId).toList();
    }

    /**
     * Whether an admin may see a person's account at all: someone enrolled in, or a TA in, a
     * classroom they run, or themselves. A superadmin sees everyone.
     */
    public boolean canSeeUser(Long adminId, Long userId) {
        if (isSuperAdmin()) return true;
        if (adminId != null && adminId.equals(userId)) return true;
        List<Long> managed = classrooms.findManagedBy(adminId).stream()
            .map(Classroom::getClassroomId).toList();
        return !managed.isEmpty() && (members.existsByUserUserIdAndClassroomIdIn(userId, managed)
            || classrooms.isTaInAny(userId, managed));
    }

    /** As {@link #canSeeUser}, answering "no such user" so an id does not confirm an account. */
    public void requireVisibleUser(Long adminId, Long userId) {
        if (!canSeeUser(adminId, userId)) throw ApiException.notFound("No such user");
    }

    public boolean isMember(Long classroomId, Long userId) {
        return classroomId != null && userId != null
            && members.existsByClassroomIdAndUserUserId(classroomId, userId);
    }

    // ------------------------------------------------------------ admin reads

    @Transactional(readOnly = true)
    public List<ClassroomsDto.ClassroomSummary> list(Long adminId) {
        List<Classroom> visible = isSuperAdmin()
            ? classrooms.findAllByOrderByNameAsc()
            : classrooms.findManagedBy(adminId);
        return visible.stream().map(this::summarise).toList();
    }

    @Transactional(readOnly = true)
    public ClassroomsDto.ClassroomSummary detail(Long adminId, Long classroomId) {
        return summarise(requireManaged(adminId, classroomId));
    }

    @Transactional(readOnly = true)
    public List<ClassroomsDto.Member> members(Long adminId, Long classroomId) {
        requireManaged(adminId, classroomId);
        List<ClassroomsDto.Member> out = new ArrayList<>();
        for (ClassroomMember member : members.findByClassroom(classroomId)) {
            User user = member.getUser();
            out.add(new ClassroomsDto.Member(user.getUserId(), user.getUsername(),
                user.getFullName(), user.getEmail(), member.getDomjudgeUsername(),
                member.getDomjudgeLogin() != null, member.getJoinedAt()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ClassroomsDto.StaffMember> staff(Long adminId, Long classroomId) {
        Classroom classroom = requireManaged(adminId, classroomId);
        List<ClassroomsDto.StaffMember> out = new ArrayList<>();
        if (classroom.getOwnerId() != null) {
            users.findById(classroom.getOwnerId()).ifPresent(owner -> out.add(
                new ClassroomsDto.StaffMember(owner.getUserId(), owner.getUsername(),
                    owner.getFullName(), true)));
        }
        for (Long id : classrooms.staffIds(classroomId)) {
            if (id.equals(classroom.getOwnerId())) continue;
            users.findById(id).ifPresent(u -> out.add(
                new ClassroomsDto.StaffMember(u.getUserId(), u.getUsername(), u.getFullName(),
                    false)));
        }
        return out;
    }

    // ----------------------------------------------------------- admin writes

    @Transactional
    public ClassroomsDto.ClassroomSummary create(Long adminId, ClassroomsDto.ClassroomRequest req,
                                                 HttpServletRequest httpReq) {
        String url = normaliseUrl(req.domjudgeUrl());
        requireUrlFree(url, null);

        String username = trimToNull(req.serviceUsername());
        String password = username == null ? null : emptyToNull(req.servicePassword());
        if (username != null && password == null) {
            throw ApiException.badRequest("Give the service account's password, or leave the "
                + "username empty to run without one.");
        }
        judges.check(url, username, password);

        Classroom classroom = classrooms.save(Classroom.builder()
            .name(req.name().trim())
            .description(trimToNull(req.description()))
            .domjudgeUrl(url)
            .serviceUsername(username)
            .servicePassword(password == null ? null : credentials.seal(password))
            .ownerId(adminId)
            .build());

        auditService.recordIn(classroom.getClassroomId(), adminId, AuditService.CLASSROOM_CREATED, "CLASSROOM",
            classroom.getClassroomId() + ":" + classroom.getName(), httpReq);
        return summarise(classroom);
    }

    @Transactional
    public ClassroomsDto.ClassroomSummary update(Long adminId, Long classroomId,
                                                 ClassroomsDto.ClassroomRequest req,
                                                 HttpServletRequest httpReq) {
        Classroom classroom = requireManaged(adminId, classroomId);
        String url = normaliseUrl(req.domjudgeUrl());
        requireUrlFree(url, classroomId);

        // The judge is what every login in the classroom was verified against. Pointing an
        // existing classroom at a different instance would leave those logins attached to
        // accounts that do not exist there, and its events naming contests that are not there.
        if (classroom.getDomjudgeUrl() != null
                && !classroom.getDomjudgeUrl().equalsIgnoreCase(url)
                && (members.countByClassroomId(classroomId) > 0
                    || events.countByClassroomId(classroomId) > 0)) {
            throw ApiException.badRequest("This classroom already has students or events on "
                + classroom.getDomjudgeUrl() + ". Create a new classroom for the other judge.");
        }

        String username = trimToNull(req.serviceUsername());
        String sealed;
        if (username == null || "".equals(req.servicePassword())) {
            username = null;
            sealed = null;
        } else if (req.servicePassword() == null) {
            sealed = classroom.getServicePassword();
            if (sealed == null) {
                throw ApiException.badRequest("Give the service account's password.");
            }
        } else {
            sealed = credentials.seal(req.servicePassword());
        }

        judges.check(url, username, sealed == null ? null : credentials.open(sealed));

        classroom.setName(req.name().trim());
        classroom.setDescription(trimToNull(req.description()));
        classroom.setDomjudgeUrl(url);
        classroom.setServiceUsername(username);
        classroom.setServicePassword(sealed);
        classrooms.save(classroom);
        judges.evict(classroomId);

        auditService.recordIn(classroomId, adminId, AuditService.CLASSROOM_UPDATED, "CLASSROOM",
            classroomId + ":" + classroom.getName(), httpReq);
        return summarise(classroom);
    }

    /** Archived, never deleted: its events and their session logs hang off it. */
    @Transactional
    public void archive(Long adminId, Long classroomId, boolean active, HttpServletRequest httpReq) {
        Classroom classroom = requireManaged(adminId, classroomId);
        classroom.setIsActive(active);
        classrooms.save(classroom);
        auditService.recordIn(classroomId, adminId, AuditService.CLASSROOM_ARCHIVED, "CLASSROOM",
            classroomId + ":" + (active ? "restored" : "archived"), httpReq);
    }

    /** Re-checks a saved classroom's judge, for the admin page's status line. */
    public ClassroomsDto.JudgeCheck checkJudge(Long adminId, Long classroomId) {
        Classroom classroom = requireManaged(adminId, classroomId);
        try {
            String version = judges.check(classroom.getDomjudgeUrl(),
                classroom.getServiceUsername(), credentials.open(classroom.getServicePassword()));
            return new ClassroomsDto.JudgeCheck(true, version, null);
        } catch (ApiException e) {
            return new ClassroomsDto.JudgeCheck(false, null, e.getMessage());
        }
    }

    @Transactional
    public ClassroomsDto.Member addMember(Long adminId, Long classroomId, Long userId,
                                          HttpServletRequest httpReq) {
        requireManaged(adminId, classroomId);
        User user = users.findById(userId)
            .orElseThrow(() -> ApiException.notFound("No CPIntel user with id " + userId + "."));
        ClassroomMember member = enroll(classroomId, user);
        auditService.recordIn(classroomId, adminId, AuditService.CLASSROOM_MEMBER_ADDED, "CLASSROOM",
            classroomId + ":" + userId, httpReq);
        return new ClassroomsDto.Member(user.getUserId(), user.getUsername(), user.getFullName(),
            user.getEmail(), member.getDomjudgeUsername(),
            credentials.exists(classroomId, userId), member.getJoinedAt());
    }

    /**
     * Takes a student out of a classroom: its teams, and its judge login, go with it.
     *
     * <p>Their past sessions and submissions stay — those are the classroom's record of what
     * happened, not the student's membership.
     */
    @Transactional
    public void removeMember(Long adminId, Long classroomId, Long userId,
                             HttpServletRequest httpReq) {
        requireManaged(adminId, classroomId);
        groupMembers.deleteFromClassroom(classroomId, userId);
        members.deleteByClassroomIdAndUserUserId(classroomId, userId);
        credentials.delete(classroomId, userId);
        auditService.recordIn(classroomId, adminId, AuditService.CLASSROOM_MEMBER_REMOVED, "CLASSROOM",
            classroomId + ":" + userId, httpReq);
    }

    @Transactional
    public void addStaff(Long adminId, Long classroomId, Long userId, HttpServletRequest httpReq) {
        requireManaged(adminId, classroomId);
        User user = users.findById(userId)
            .orElseThrow(() -> ApiException.notFound("No CPIntel user with id " + userId + "."));
        if (!Roles.isAdminLevel(user.getRole())) {
            throw ApiException.badRequest(user.getUsername() + " is not an admin. Only admins "
                + "can run a classroom.");
        }
        classrooms.addStaff(classroomId, userId);
        auditService.recordIn(classroomId, adminId, AuditService.CLASSROOM_STAFF_ADDED, "CLASSROOM",
            classroomId + ":" + userId, httpReq);
    }

    @Transactional
    public void removeStaff(Long adminId, Long classroomId, Long userId,
                            HttpServletRequest httpReq) {
        requireManaged(adminId, classroomId);
        classrooms.removeStaff(classroomId, userId);
        auditService.recordIn(classroomId, adminId, AuditService.CLASSROOM_STAFF_REMOVED, "CLASSROOM",
            classroomId + ":" + userId, httpReq);
    }

    // -------------------------------------------------------------- enrolment

    /** Enrols someone, or returns their existing enrolment. Idempotent. */
    @Transactional
    public ClassroomMember enroll(Long classroomId, User user) {
        return members.findByClassroomIdAndUserUserId(classroomId, user.getUserId())
            .orElseGet(() -> members.save(ClassroomMember.builder()
                .classroomId(classroomId)
                .user(user)
                .build()));
    }

    @Transactional
    public ClassroomMember enroll(Long classroomId, Long userId) {
        User user = users.findById(userId)
            .orElseThrow(() -> ApiException.notFound("No CPIntel user with id " + userId + "."));
        return enroll(classroomId, user);
    }

    /** Records which judge login is attached, so listings need not decrypt it. */
    @Transactional
    public void recordLogin(Long classroomId, Long userId, String domjudgeUsername) {
        ClassroomMember member = enroll(classroomId, userId);
        member.setDomjudgeUsername(domjudgeUsername);
        members.save(member);
    }

    // --------------------------------------------------------------- students

    @Transactional(readOnly = true)
    public List<ClassroomsDto.MyClassroom> mine(Long userId) {
        List<ClassroomsDto.MyClassroom> out = new ArrayList<>();
        for (Long id : members.classroomIdsOf(userId)) {
            Classroom classroom = classrooms.findById(id).orElse(null);
            if (classroom == null || !Boolean.TRUE.equals(classroom.getIsActive())) continue;
            ClassroomMember member = members.findByClassroomIdAndUserUserId(id, userId).orElse(null);
            boolean attached = member != null && member.getDomjudgeLogin() != null;
            out.add(new ClassroomsDto.MyClassroom(id, classroom.getName(),
                classroom.getDescription(), attached,
                attached ? member.getDomjudgeUsername() : null));
        }
        out.sort(Comparator.comparing(ClassroomsDto.MyClassroom::name,
            String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** The active classrooms this person is enrolled in whose judge is set. */
    @Transactional(readOnly = true)
    public List<Classroom> judgeClassroomsOf(Long userId) {
        List<Classroom> out = new ArrayList<>();
        for (Long id : members.classroomIdsOf(userId)) {
            classrooms.findById(id)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .filter(c -> StringUtils.hasText(c.getDomjudgeUrl()))
                .ifPresent(out::add);
        }
        return out;
    }

    /** The classroom whose judge a pasted link points at, if any. Longest root wins. */
    @Transactional(readOnly = true)
    public Optional<Classroom> forJudgeLink(String link) {
        if (link == null) return Optional.empty();
        String wanted = link.trim().toLowerCase(Locale.ROOT);
        return classrooms.findAll().stream()
            .filter(c -> StringUtils.hasText(c.getDomjudgeUrl()))
            .filter(c -> {
                String root = c.getDomjudgeUrl().toLowerCase(Locale.ROOT);
                return wanted.equals(root) || wanted.startsWith(root + "/");
            })
            .max(Comparator.comparingInt(c -> c.getDomjudgeUrl().length()));
    }

    // ---------------------------------------------------------------- helpers

    private ClassroomsDto.ClassroomSummary summarise(Classroom c) {
        Long id = c.getClassroomId();
        return new ClassroomsDto.ClassroomSummary(id, c.getName(), c.getDescription(),
            c.getDomjudgeUrl(), c.getServiceUsername(), c.getServicePassword() != null,
            Boolean.TRUE.equals(c.getIsActive()), c.getOwnerId(),
            members.countByClassroomId(id), groups.countByClassroomId(id),
            events.countByClassroomId(id), c.getCreatedAt());
    }

    private void requireUrlFree(String url, Long self) {
        classrooms.findByDomjudgeUrl(url)
            .filter(other -> !other.getClassroomId().equals(self))
            .ifPresent(other -> {
                throw ApiException.conflict("The classroom \"" + other.getName() + "\" already "
                    + "uses " + url + ". Each classroom has its own judge.");
            });
    }

    /**
     * The judge's root as one canonical string, so uniqueness means something: no trailing
     * slash, and no {@code /api} or {@code /api/v4} that an admin pasted from their browser.
     */
    static String normaliseUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        url = url.replaceAll("/+$", "");
        url = url.replaceAll("(?i)/api(/v4)?$", "");
        url = url.replaceAll("/+$", "");
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("'" + raw + "' is not a judge address. Expected "
                + "something like https://judge.example.edu");
        }
        return url;
    }

    private static boolean isSuperAdmin() {
        return Roles.isSuperAdmin(SecurityContextHolder.getContext().getAuthentication());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
