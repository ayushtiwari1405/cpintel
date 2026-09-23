package com.cpintel.service;

import com.cpintel.entity.User;
import com.cpintel.entity.VerificationToken;
import com.cpintel.exception.ApiException;
import com.cpintel.mail.EmailTemplates;
import com.cpintel.mail.MailService;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.repository.jpa.VerificationTokenRepository;
import com.cpintel.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Changing your own password: by asking for a link, or by knowing the current one.
 *
 * <p><b>The first password is not this service's business.</b> An account is created by an
 * administrator, and its first password is handed over with the username — in person, on a
 * deployment sheet, however that particular room works. Nothing is emailed to bootstrap an
 * account, because the mail would be a live credential sitting in a mailbox, and because the
 * deployments this runs on cannot always reach a mail server at the moment the accounts are
 * made. What this adds is everything after that: the password becomes the owner's, and they
 * can change it without asking anyone.
 *
 * <p><b>Three things make a reset link safe enough to email.</b>
 *
 * <ul>
 *   <li><b>Only its hash is stored.</b> The raw token exists in the email and nowhere else, so
 *       a dump of {@code verification_tokens} contains nothing redeemable. The column was
 *       already unique and 200 characters wide, and a SHA-256 hex digest fits it.
 *   <li><b>It is single use and short lived.</b> Redeeming it marks it used in the same
 *       transaction that changes the password, and issuing a new one retires the old, so a
 *       mailbox holding four reset mails holds one working link.
 *   <li><b>Redeeming it ends every session.</b> Somebody resetting a password because they
 *       believe it is known to somebody else has not finished until the other party is signed
 *       out, and making them find a "sign out everywhere" button afterwards means most people
 *       never will.
 * </ul>
 *
 * <p><b>Nothing here says whether an account exists.</b> Every outcome of a forgotten-password
 * request is the same answer — the mail is sent or quietly not sent, and the response is
 * identical either way. The endpoint is open to the internet, and the alternative turns it into
 * a way to test a list of addresses against the roster of whoever is being examined.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordService {

    /** Matches chk_vt_type in V1. */
    private static final String RESET = "PASSWORD_RESET";

    private static final int TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final VerificationTokenRepository tokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final MailService mail;

    private final SecureRandom random = new SecureRandom();

    /** How long a link is worth. Long enough to find the mail, short enough to matter. */
    @Value("${cpintel.auth.reset-token-minutes:60}")
    private long resetMinutes;

    // ------------------------------------------------------------- forgotten

    /**
     * Issues a reset link for whoever owns this address, if anybody does.
     *
     * Returns nothing, and the caller answers the same way regardless. See the class note.
     */
    @Transactional
    public void requestReset(String rawEmail, HttpServletRequest httpReq) {
        String email = rawEmail == null ? "" : rawEmail.trim();

        // Case-insensitively, exactly as sign-in resolves an address.
        //
        // Registration stores an address as it was typed, and the admin console lower-cases
        // one, so both forms are genuinely present in the users table. Matching exactly here
        // while sign-in folds case gave the worst possible split: an account created with a
        // capital in its address could sign in and could never reset, and the enumeration-safe
        // answer meant it reported success while doing nothing at all.
        Optional<User> found = userRepository.findByEmailIgnoreCase(email);

        if (found.isEmpty()) {
            // Logged, because a burst of these against addresses that do not exist is worth
            // seeing. Not audited against a user, because there is no user.
            log.info("Password reset asked for an address with no account: {}", email);
            return;
        }

        User user = found.get();
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            // A deactivated account is not a door to reopen with a link. The person needs an
            // administrator, and telling them so through a mail they can act on would be
            // telling anybody who typed the address that the account exists.
            log.info("Password reset asked for deactivated account {}", user.getUserId());
            return;
        }

        String raw = issue(user);
        String link = mail.baseUrl() + "/reset-password?token="
            + URLEncoder.encode(raw, StandardCharsets.UTF_8);

        EmailTemplates.Message message =
            EmailTemplates.passwordReset(user.getFullName(), link, resetMinutes);
        mail.send(user.getEmail(), message.subject(), message.text(), message.html());

        auditService.record(user.getUserId(), AuditService.PASSWORD_RESET_REQUESTED, "USER",
            String.valueOf(user.getUserId()), httpReq);
        log.info("Issued a password reset link for user {}", user.getUserId());
    }

    /**
     * Spends a link and sets the password it was issued for.
     *
     * <p>Unlike the request above, this one answers honestly when the token is wrong: the
     * person holding a broken link needs to know it is broken, and a token that does not
     * resolve reveals nothing about who any account belongs to.
     */
    @Transactional
    public void completeReset(String rawToken, String newPassword, HttpServletRequest httpReq) {
        VerificationToken token = tokenRepository.findByToken(hash(rawToken))
            .filter(t -> RESET.equals(t.getTokenType()))
            .orElseThrow(() -> ApiException.badRequest(
                "That reset link is not valid. Ask for a new one from the sign-in page."));

        if (Boolean.TRUE.equals(token.getUsed())) {
            throw ApiException.badRequest(
                "That reset link has already been used. Ask for a new one from the sign-in page.");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest(
                "That reset link has expired. Ask for a new one from the sign-in page.");
        }

        User user = token.getUser();
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw ApiException.forbidden(
                "This account is deactivated. Ask an administrator to reactivate it.");
        }

        requireUsablePassword(newPassword);

        token.setUsed(true);
        tokenRepository.save(token);

        apply(user, newPassword, "using a reset link");
        auditService.record(user.getUserId(), AuditService.PASSWORD_RESET, "USER",
            String.valueOf(user.getUserId()), httpReq);
        log.info("User {} completed a password reset", user.getUserId());
    }

    // ----------------------------------------------------------- knowing it

    /**
     * Changes the password of somebody who is signed in and knows the current one.
     *
     * The current password is required even though the caller already holds a valid token,
     * because a token can be a borrowed laptop and the point of this check is that the person
     * at the keyboard is the owner rather than whoever sat down after them.
     */
    @Transactional
    public void change(Long userId, String currentPassword, String newPassword,
                       HttpServletRequest httpReq) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("No such account"));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            auditService.record(userId, AuditService.PASSWORD_CHANGE_FAILED, "USER",
                String.valueOf(userId), httpReq);
            throw ApiException.unauthorized("That is not your current password.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("That is already your password.");
        }

        requireUsablePassword(newPassword);
        apply(user, newPassword, "from your profile");

        auditService.record(userId, AuditService.PASSWORD_CHANGED, "USER",
            String.valueOf(userId), httpReq);
        log.info("User {} changed their password", userId);
    }

    // ---------------------------------------------------------------- shared

    /**
     * Writes the new password and closes everything the old one had open.
     *
     * The three steps belong together and are never done apart: the hash, the sessions, and
     * any reset link still outstanding. Leaving any of them out leaves a way back in for
     * whoever the change was made because of.
     */
    private void apply(User user, String newPassword, String how) {
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        userRepository.save(user);

        refreshTokenRepository.revokeAllByUserId(user.getUserId());
        jwtService.revokeUserTokens(user.getUserId());
        tokenRepository.invalidateOutstanding(user.getUserId(), RESET);

        EmailTemplates.Message notice =
            EmailTemplates.passwordChanged(user.getFullName(), how);
        mail.send(user.getEmail(), notice.subject(), notice.text(), notice.html());
    }

    /** Creates and stores a token, returning the raw value that goes in the email. */
    private String issue(User user) {
        // Any link already out there stops working the moment a new one is asked for, so a
        // mailbox with four reset mails in it has one working link rather than four.
        tokenRepository.invalidateOutstanding(user.getUserId(), RESET);

        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        tokenRepository.save(VerificationToken.builder()
            .user(user)
            .token(hash(raw))
            .tokenType(RESET)
            .expiresAt(Instant.now().plus(resetMinutes, ChronoUnit.MINUTES))
            .used(false)
            .build());

        return raw;
    }

    /**
     * Rejects the passwords that are not worth the trouble of hashing.
     *
     * A length floor and nothing else. Composition rules — a digit, a symbol, a capital — push
     * people towards one predictable mutation of a word they already use, and this deployment's
     * real exposure is a shared first password that nobody changed, which no rule about
     * punctuation addresses.
     */
    private void requireUsablePassword(String password) {
        if (!StringUtils.hasText(password) || password.length() < 8) {
            throw ApiException.badRequest("A password has to be at least 8 characters.");
        }
        if (password.length() > 200) {
            throw ApiException.badRequest("That password is too long.");
        }
    }

    private String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
