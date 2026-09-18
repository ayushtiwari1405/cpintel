package com.cpintel.admin;

import com.cpintel.entity.UnifiedScore;
import com.cpintel.entity.User;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.security.Roles;
import com.cpintel.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * How the first super administrator comes to exist.
 *
 * Nothing else in the system can produce one. Sign-up creates ordinary users and is closed by
 * default; the console can promote someone to ADMIN but not past it. So the top of the
 * permission tree is anchored outside the application, in the deployment's own configuration,
 * where changing it requires access to the server rather than access to a screen.
 *
 * <p>Two ways in, depending on whether the named account already exists:
 *
 * <ul>
 *   <li><b>It exists</b> — it is promoted. Nothing else about it changes except reactivation,
 *       which is what makes this a genuine way back in after the last admin locked themselves
 *       out.</li>
 *   <li><b>It does not</b> — it is created, but only if a password was supplied in
 *       {@code cpintel.admin.bootstrap-password}. There is no default: a deployment with a
 *       well-known administrator password is worse than one with no administrator at all,
 *       because the second failure is loud and the first is silent.</li>
 * </ul>
 *
 * Idempotent, and safe to leave configured. An account that is already a super admin is left
 * alone, and the setting never touches any other account.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final UnifiedScoreRepository unifiedScoreRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Value("${cpintel.admin.bootstrap-email:}")
    private String bootstrapEmail;

    /** Only used when the account does not exist yet. Blank means "do not create one". */
    @Value("${cpintel.admin.bootstrap-password:}")
    private String bootstrapPassword;

    @Value("${cpintel.admin.bootstrap-username:superadmin}")
    private String bootstrapUsername;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(bootstrapEmail)) {
            if (userRepository.countByRole(Roles.SUPER_ADMIN) == 0)
                log.warn("No super admin exists and cpintel.admin.bootstrap-email is unset. "
                    + "Nobody can assign roles or create accounts. Set CPINTEL_ADMIN_EMAIL "
                    + "(and CPINTEL_ADMIN_PASSWORD for a new deployment) and restart.");
            return;
        }

        String email = bootstrapEmail.trim().toLowerCase(java.util.Locale.ROOT);
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            createSuperAdmin(email);
            return;
        }

        if (Roles.SUPER_ADMIN.equals(user.getRole())) {
            log.debug("Bootstrap account {} is already a super admin", email);
            return;
        }

        String previous = user.getRole();
        user.setRole(Roles.SUPER_ADMIN);
        // An account nobody can sign in to is not much of an administrator. Reactivating here
        // is what makes this a genuine way back in after the last admin was locked out.
        user.setIsActive(true);
        userRepository.save(user);

        auditService.record(user.getUserId(), AuditService.ADMIN_BOOTSTRAPPED, "USER",
            user.getUserId() + ":" + previous + "->" + Roles.SUPER_ADMIN);
        log.info("Promoted {} from {} to SUPER_ADMIN via cpintel.admin.bootstrap-email",
            email, previous);
    }

    private void createSuperAdmin(String email) {
        if (!StringUtils.hasText(bootstrapPassword)) {
            log.warn("cpintel.admin.bootstrap-email is set to {}, but no account has that "
                + "address and cpintel.admin.bootstrap-password is unset, so one cannot be "
                + "created. Set CPINTEL_ADMIN_PASSWORD and restart.", email);
            return;
        }
        if (bootstrapPassword.length() < 8) {
            log.error("cpintel.admin.bootstrap-password is shorter than 8 characters. "
                + "Refusing to create the super admin {}.", email);
            return;
        }

        String username = StringUtils.hasText(bootstrapUsername)
            ? bootstrapUsername.trim() : "superadmin";
        if (userRepository.existsByUsername(username)) {
            log.error("Cannot create super admin {}: the username '{}' is taken. "
                + "Set cpintel.admin.bootstrap-username to something else.", email, username);
            return;
        }

        User user = userRepository.save(User.builder()
            .username(username)
            .email(email)
            .passwordHash(passwordEncoder.encode(bootstrapPassword))
            .role(Roles.SUPER_ADMIN)
            .isActive(true)
            .isVerified(true)
            .build());

        unifiedScoreRepository.save(UnifiedScore.builder().user(user).build());

        auditService.record(user.getUserId(), AuditService.ADMIN_BOOTSTRAPPED, "USER",
            user.getUserId() + ":created");
        log.info("Created super admin {} (username '{}') from configuration. "
            + "Change this password after signing in.", email, username);
    }
}
