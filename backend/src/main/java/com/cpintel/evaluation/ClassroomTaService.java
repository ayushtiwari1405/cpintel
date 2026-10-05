package com.cpintel.evaluation;

import com.cpintel.admin.AdminUserService;
import com.cpintel.classrooms.ClassroomService;
import com.cpintel.entity.UnifiedScore;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ClassroomRepository;
import com.cpintel.repository.jpa.ExamTaAssignmentRepository;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.security.Roles;
import com.cpintel.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A classroom's teaching assistants.
 *
 * <p>A TA is an ordinary account linked to the classroom, not a role: the same person can be a
 * student in one classroom and mark in another. Admins are not TAs — they run the classroom
 * from the console and can already mark everything there.
 *
 * <p>Adding one follows the roster's rules for accounts. An existing account is found by email,
 * then by username, and linked as it is. Only when nothing matches is a new one created, with a
 * generated password the admin hands over and which is shown once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassroomTaService {

    /** As AdminDto.CreateUserRequest and the roster import. */
    private static final Pattern USERNAME_OK = Pattern.compile("^[a-zA-Z0-9_]{3,50}$");
    private static final Pattern EMAIL_OK = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ClassroomService classrooms;
    private final ClassroomRepository classroomRepository;
    private final ExamTaAssignmentRepository assignments;
    private final UserRepository users;
    private final UnifiedScoreRepository unifiedScores;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final EvaluationLocks locks;

    @Transactional(readOnly = true)
    public List<EvaluationDto.Ta> list(Long adminId, Long classroomId) {
        classrooms.requireManaged(adminId, classroomId);
        return tasOf(classroomId);
    }

    /** The classroom's TAs, by username. No access check: callers have made theirs. */
    @Transactional(readOnly = true)
    public List<EvaluationDto.Ta> tasOf(Long classroomId) {
        List<EvaluationDto.Ta> out = new ArrayList<>();
        for (User user : users.findAllById(classroomRepository.taIds(classroomId))) {
            out.add(toTa(user));
        }
        out.sort(Comparator.comparing(EvaluationDto.Ta::username, UsernameOrder.NATURAL));
        return out;
    }

    @Transactional
    public EvaluationDto.TaAdded add(Long adminId, Long classroomId, EvaluationDto.TaRequest req,
                                     HttpServletRequest httpReq) {
        classrooms.requireManaged(adminId, classroomId);

        String email = trimToNull(req.email());
        if (email != null) email = email.toLowerCase(Locale.ROOT);
        String username = trimToNull(req.username());
        if (email == null && username == null) {
            throw ApiException.badRequest("Give the TA's email or username.");
        }
        if (email != null && !EMAIL_OK.matcher(email).matches()) {
            throw ApiException.badRequest("'" + email + "' is not a usable email address.");
        }

        String lookupEmail = email;
        String lookupUsername = username;
        Optional<User> existing = Optional.<User>empty()
            .or(() -> lookupEmail == null ? Optional.empty() : users.findByEmail(lookupEmail))
            .or(() -> lookupUsername == null ? Optional.empty() : users.findByUsername(lookupUsername));

        boolean created = false;
        String password = null;
        User user;
        if (existing.isPresent()) {
            user = existing.get();
            if (Roles.isAdminLevel(user.getRole())) {
                throw ApiException.badRequest(user.getUsername() + " is an admin. Admins run "
                    + "classrooms from the console and can already mark everything in them; "
                    + "add them to this classroom's admins instead.");
            }
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                throw ApiException.badRequest(user.getUsername() + "'s account is switched off. "
                    + "Switch it on from Users first.");
            }
        } else {
            if (email == null) {
                throw ApiException.badRequest("Nobody is called '" + username + "'. A new "
                    + "account needs an email address.");
            }
            if (username == null) username = email.substring(0, email.indexOf('@'))
                .replaceAll("[^a-zA-Z0-9_]", "");
            if (!USERNAME_OK.matcher(username).matches()) {
                throw ApiException.badRequest("'" + username + "' can't be a username: use 3 to "
                    + "50 letters, numbers and underscores.");
            }
            if (users.existsByUsername(username)) {
                throw ApiException.conflict("The username '" + username + "' is taken by an "
                    + "account with a different email. Give another username, or that "
                    + "account's email to link it.");
            }
            password = AdminUserService.generatePassword();
            user = users.save(User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .fullName(trimToNull(req.fullName()))
                .role(Roles.USER)
                .isActive(true)
                .isVerified(false)
                .build());
            // Mirrors registration and every other way an account is made.
            unifiedScores.save(UnifiedScore.builder().user(user).build());
            created = true;
            auditService.recordIn(classroomId, adminId, AuditService.USER_CREATED, "USER",
                user.getUserId() + ":" + Roles.USER + ":ta", httpReq);
        }

        boolean linked = classroomRepository.addTa(classroomId, user.getUserId(), adminId) > 0;
        if (linked) {
            auditService.recordIn(classroomId, adminId, AuditService.CLASSROOM_TA_ADDED,
                "CLASSROOM", classroomId + ":" + user.getUserId(), httpReq);
        }

        String message = created ? "Created " + user.getUsername() + " and made them a TA here."
            : linked ? "Linked the existing account " + user.getUsername() + " as a TA."
            : user.getUsername() + " is already a TA here.";
        return new EvaluationDto.TaAdded(toTa(user), created, password, message);
    }

    /** Takes a TA out of the classroom, with whatever they were marking in it. Marks stay. */
    @Transactional
    public void remove(Long adminId, Long classroomId, Long userId, HttpServletRequest httpReq) {
        classrooms.requireManaged(adminId, classroomId);
        assignments.deleteInClassroom(classroomId, userId);
        locks.unfreezeInClassroom(classroomId, userId);
        if (classroomRepository.removeTa(classroomId, userId) > 0) {
            auditService.recordIn(classroomId, adminId, AuditService.CLASSROOM_TA_REMOVED,
                "CLASSROOM", classroomId + ":" + userId, httpReq);
        }
    }

    private static EvaluationDto.Ta toTa(User user) {
        return new EvaluationDto.Ta(user.getUserId(), user.getUsername(), user.getFullName(),
            user.getEmail());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
