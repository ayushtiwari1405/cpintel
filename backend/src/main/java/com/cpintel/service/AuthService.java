package com.cpintel.service;

import com.cpintel.dto.AuthDto;
import com.cpintel.dto.UserDto;
import com.cpintel.entity.RefreshToken;
import com.cpintel.entity.UnifiedScore;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.security.JwtService;
import com.cpintel.security.PasswordPolicy;
import com.cpintel.config.AppMetrics;
import com.cpintel.security.RateLimitService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import com.cpintel.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    /** Login attempts counted against the account, not the caller's address. */
    private static final String ACCOUNT_BUCKET = "login-account";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UnifiedScoreRepository unifiedScoreRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final RateLimitService rateLimiter;
    private final AppMetrics metrics;
    private final com.cpintel.events.ExamLoginService examLogin;
    private final com.cpintel.events.ExamAccessService examAccess;
    private final com.cpintel.repository.jpa.GroupContestRepository events;
    private final com.cpintel.events.ExamLockoutService examLockout;

    /**
     * Whether anyone may create their own account.
     *
     * Off by default. With it off the only way in is a super admin creating the account, which
     * is what makes the member list of a deployment something somebody decided rather than
     * something that accumulated. The endpoint stays mapped and public so that turning
     * sign-up off produces an honest "registration is closed" answer instead of a 404 that
     * looks like the server is broken.
     */
    @Value("${cpintel.auth.registration-enabled:false}")
    private boolean registrationEnabled;

    @Transactional
    public AuthDto.AuthResponse register(AuthDto.RegisterRequest req, HttpServletRequest httpReq) {
        if (!registrationEnabled) {
            auditService.record(null, AuditService.REGISTER_BLOCKED, "EMAIL", req.getEmail(), httpReq);
            throw ApiException.forbidden(
                "Registration is closed. Ask an administrator to create your account.");
        }

        if (userRepository.existsByEmailIgnoreCase(req.getEmail()))
            throw ApiException.conflict("Email already registered");
        if (userRepository.existsByUsernameIgnoreCase(req.getUsername()))
            throw ApiException.conflict("Username already taken");
        PasswordPolicy.require(req.getPassword(), req.getUsername(), req.getEmail());

        User user = User.builder()
            .username(req.getUsername())
            .email(req.getEmail())
            .passwordHash(passwordEncoder.encode(req.getPassword()))
            .fullName(req.getFullName())
            .role(Roles.USER)
            .isActive(true)
            .isVerified(false)
            .build();

        user = userRepository.save(user);

        // Oracle created this row from the trg_users_create_unified_score trigger.
        // Postgres could do the same, but the rest of the analytics engine now lives
        // in Java, and a row that appears from nowhere is the kind of thing that makes
        // registration hard to reason about. Created explicitly instead, in the same
        // transaction. (The roadmap skeleton, which had its own trigger, was already
        // seeded lazily by RoadmapService.ensureSeeded.)
        unifiedScoreRepository.save(UnifiedScore.builder().user(user).build());

        // The id, not the address: application logs are kept, shipped and read far more widely
        // than the audit trail, and an address is personal data the id does not need.
        log.info("New user registered: user {}", user.getUserId());
        auditService.record(user.getUserId(), AuditService.REGISTER, "USER",
            String.valueOf(user.getUserId()), httpReq);

        return buildAuthResponse(user, httpReq);
    }

    @Transactional
    public AuthDto.AuthResponse login(AuthDto.LoginRequest req, HttpServletRequest httpReq) {
        // Limited per account as well as per address.
        //
        // The address limit in RateLimitFilter stops one machine hammering the login, but says
        // nothing about a thousand machines each making four attempts against the same account,
        // which is the shape credential stuffing actually takes. Counting against the identifier
        // closes that, and the counter is cleared on success below so an honest person who
        // mistyped their password several times is not left on the edge of a lockout.
        //
        // Keyed on the normalised identifier rather than on the resolved account, because the
        // limit has to apply before anything is looked up — including to identifiers that
        // match nothing, which is most of what an attacker sends. One consequence is worth
        // naming: somebody guessing at an account can spend its username budget and its email
        // budget separately. That halves the limit's strength and is still a great deal
        // stronger than not counting per account at all, and closing it properly would mean
        // resolving the account before deciding whether to rate-limit, which is the lookup the
        // limit exists to protect.
        String identifier = normalise(req.getIdentifier());
        if (!rateLimiter.tryAcquire(ACCOUNT_BUCKET, identifier, rateLimiter.rules().getAccount())) {
            auditService.record(null, AuditService.LOGIN_THROTTLED, "IDENTIFIER", identifier, httpReq);
            metrics.throttled(ACCOUNT_BUCKET);
            throw ApiException.tooManyRequests(
                "Too many sign-in attempts for this account. Try again in "
                + rateLimiter.retryAfterSeconds(ACCOUNT_BUCKET, identifier) + " seconds.");
        }

        User user = resolve(identifier);

        // Failures are recorded against what was typed, not a user id, because the interesting
        // case for an admin reading this back is exactly the one where no such account exists.
        // The password is never part of the record.
        if (user == null) {
            // The same Argon2 comparison a real account costs, so that how long the refusal
            // takes does not say whether the name exists.
            passwordEncoder.matches(req.getPassword(), standInHash());
            auditService.record(null, AuditService.LOGIN_FAILED, "IDENTIFIER", identifier, httpReq);
            throw ApiException.unauthorized("Invalid credentials");
        }

        // The examination password on this person's slip signs them in to examination mode for
        // that one paper. Tried before the account password because it is the cheap check — a
        // decrypt and a constant-time compare, against papers whose sign-in window is open — and
        // the expensive one is Argon2id at 64 MB a hash: a room of two hundred signing in at the
        // start of a paper used to pay that two hundred times before reaching the check that
        // let them in. The codes are generated at random, so no account password collides.
        var exam = examLogin.match(user.getUserId(), req.getPassword());
        if (exam.isEmpty()
                && !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            auditService.record(user.getUserId(), AuditService.LOGIN_FAILED, "USER",
                "bad-password", httpReq);
            throw ApiException.unauthorized("Invalid credentials");
        }

        // Only now, to somebody holding one of this account's passwords. Said before the
        // password was checked, it told anybody who typed a name that the account exists.
        if (!user.getIsActive()) {
            auditService.record(user.getUserId(), AuditService.LOGIN_FAILED, "USER",
                "deactivated", httpReq);
            throw ApiException.forbidden("Account is deactivated");
        }

        if (exam.isPresent()) {
            rateLimiter.reset(ACCOUNT_BUCKET, identifier);
            requireDesktopAppIfAsked(exam.get(), user, httpReq);
            examAccess.onExamSignIn(exam.get(), user.getUserId());
            auditService.record(user.getUserId(), AuditService.EXAM_SIGN_IN, "EXAM",
                String.valueOf(exam.get().getContestId()), httpReq);
            log.info("User {} signed in to examination {}", user.getUserId(),
                exam.get().getContestId());
            return buildAuthResponse(user, httpReq, exam.get());
        }

        rateLimiter.reset(ACCOUNT_BUCKET, identifier);

        // The right account password, but this person's examination is running: for its length
        // the only way in is the examination password on their slip. Checked after the password,
        // so the refusal says nothing about anybody's examinations to somebody guessing.
        refuseDuringExamination(user, httpReq);

        log.info("User {} logged in", user.getUserId());
        auditService.record(user.getUserId(), AuditService.LOGIN, "USER",
            String.valueOf(user.getUserId()), httpReq);
        return buildAuthResponse(user, httpReq);
    }

    // A refusal must not roll back the rotation: a refresh token that is turned away has to stay
    // spent, or it is still there to use the moment whatever refused it lifts.
    @Transactional(noRollbackFor = ApiException.class)
    public AuthDto.AuthResponse refresh(AuthDto.RefreshRequest req, HttpServletRequest httpReq) {
        RefreshToken token = refreshTokenRepository
            .findByToken(com.cpintel.common.TokenDigest.of(req.getRefreshToken()))
            .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));

        Instant now = Instant.now();
        if (token.getRevoked()) {
            var again = replaySuccessor(token, req.getRefreshToken(), now, httpReq);
            if (again.isPresent()) return again.get();
            refuseReuse(token, now, httpReq);
            throw ApiException.unauthorized("Refresh token has been revoked");
        }

        if (token.getExpiresAt().isBefore(now)) {
            refreshTokenRepository.delete(token);
            throw ApiException.unauthorized("Refresh token expired");
        }

        // Rotate: revoke old, issue new. The old one is claimed atomically: of several requests
        // presenting it at the same instant exactly one wins, and the rest are turned away as
        // the owner racing themselves (see REUSE_GRACE) rather than each being handed a session.
        if (refreshTokenRepository.claim(token.getTokenId(), now) == 0) {
            throw ApiException.unauthorized("Refresh token has been revoked");
        }

        User user = token.getUser();
        var exam = requireMayRenew(token, user, httpReq);
        return buildAuthResponse(user, httpReq, exam, token.getFamilyId(),
            jwtService.successorOf(req.getRefreshToken()));
    }

    /**
     * Whether this session may carry on, whichever way its renewal was asked for.
     *
     * @return the examination an examination session is for; null for an ordinary one
     */
    private com.cpintel.entity.GroupContest requireMayRenew(RefreshToken token, User user,
                                                            HttpServletRequest httpReq) {
        // Checked here as well as at login. Refresh is the one path that hands out a fresh
        // access token without a password, so without this an account deactivated mid-session
        // would keep renewing itself for as long as its refresh token lived.
        if (!user.getIsActive())
            throw ApiException.forbidden("Account is deactivated");

        // An examination session renews as one, and only while its paper is open. When the paper
        // ends the candidate is signed out of examination mode rather than quietly handed an
        // ordinary session they never signed in for.
        if (token.getExamId() != null) {
            var exam = events.findById(token.getExamId()).orElse(null);
            if (exam == null || !examLogin.stillOpen(exam, Instant.now())) {
                throw ApiException.unauthorized("The examination has ended. Sign in with your "
                    + "account password to see your submissions under Past examinations.");
            }
            // Checked at every renewal as well as at sign-in: a session carried into a browser,
            // or a paper switched over to desktop-only while it runs, ends here.
            requireDesktopAppIfAsked(exam, user, httpReq);
            return exam;
        }
        // An ordinary session does not renew while its holder's examination runs. The token was
        // revoked by the rotation, so this is the sign-out.
        refuseDuringExamination(user, httpReq);
        return null;
    }

    /**
     * Answers a token retired a moment ago with the successor it was exchanged for.
     *
     * <p>Tokens are single use, so a browser that never received the reply to a renewal — a
     * dropped connection, a reload while it was in flight — holds only the retired one and had
     * no way back in. The successor is derived from the token it replaced, so it can be given
     * again without any raw token ever having been stored.
     *
     * <p>Only within {@link #REUSE_GRACE} of the rotation, only to the same browser, only while
     * the successor has not itself been used or revoked, and only if the session may still be
     * renewed. The cost: somebody who copied a token and presents it inside those seconds from
     * the same user agent receives the successor too. That is still found out, because the two
     * then share one token and the second to renew it after the grace ends the family.
     */
    private java.util.Optional<AuthDto.AuthResponse> replaySuccessor(
            RefreshToken retired, String raw, Instant now, HttpServletRequest httpReq) {
        Instant rotated = retired.getRotatedAt();
        if (rotated == null || rotated.plus(REUSE_GRACE).isBefore(now)) {
            return java.util.Optional.empty();
        }
        String successor = jwtService.successorOf(raw);
        RefreshToken next = refreshTokenRepository
            .findByToken(com.cpintel.common.TokenDigest.of(successor))
            .filter(t -> !t.getRevoked() && t.getExpiresAt().isAfter(now))
            .filter(t -> java.util.Objects.equals(t.getFamilyId(), retired.getFamilyId()))
            .filter(t -> java.util.Objects.equals(t.getDeviceInfo(),
                httpReq.getHeader("User-Agent")))
            .orElse(null);
        if (next == null) return java.util.Optional.empty();

        User user = next.getUser();
        var exam = requireMayRenew(next, user, httpReq);
        return java.util.Optional.of(respond(user, exam, successor));
    }

    /**
     * How long after a rotation the old token may arrive again without being taken for theft.
     *
     * Two tabs renewing at the same moment both send the cookie they hold, and only one of them
     * can win the rotation; the other arrives a moment later with the token just retired. That is
     * the owner racing themselves, and ending their session for it would sign people out for
     * having two tabs open. A copy presented later than this is somebody else.
     */
    static final java.time.Duration REUSE_GRACE = java.time.Duration.ofSeconds(10);

    /**
     * A revoked token was presented. If it was retired by rotation, somebody already exchanged it
     * for a successor, so two parties held it — the owner and whoever copied it — and there is no
     * telling which is which. Every session renewed from that sign-in is ended, and the access
     * tokens already issued with them, so both parties have to sign in again; only one of them
     * can.
     *
     * A token revoked any other way (signing out, an admin, an examination starting) carries no
     * such signal and is simply refused.
     */
    private void refuseReuse(RefreshToken token, Instant now, HttpServletRequest httpReq) {
        Instant rotated = token.getRotatedAt();
        if (rotated == null || !rotated.plus(REUSE_GRACE).isBefore(now)) return;

        int ended = refreshTokenRepository.revokeFamily(token.getFamilyId());
        if (ended == 0) return;  // The family was already over: nothing left to protect.

        Long userId = token.getUser().getUserId();
        jwtService.revokeUserTokens(userId);
        auditService.record(userId, AuditService.REFRESH_TOKEN_REUSED, "USER",
            String.valueOf(userId), httpReq);
        log.warn("A retired refresh token for user {} was presented again; ended {} session(s) "
            + "renewed from that sign-in", userId, ended);
    }

    /**
     * Refuses an ordinary session to a candidate whose examination is running. Admins are not
     * candidates. See {@link com.cpintel.events.ExamLockoutService}.
     */
    private void refuseDuringExamination(User user, HttpServletRequest httpReq) {
        if (com.cpintel.security.Roles.isAdminLevel(user.getRole())) return;
        var lock = examLockout.lockFor(user.getUserId());
        if (lock.isEmpty()) return;
        auditService.record(user.getUserId(), AuditService.LOGIN_FAILED, "EXAM",
            String.valueOf(lock.get().examId()), httpReq);
        throw examInProgress(lock.get());
    }

    /** What a candidate held out of ordinary mode is told, and the code the client looks for. */
    public static ApiException examInProgress(com.cpintel.events.ExamLockoutService.Lock lock) {
        return new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
            EXAM_IN_PROGRESS, examInProgressMessage(lock));
    }

    public static String examInProgressMessage(com.cpintel.events.ExamLockoutService.Lock lock) {
        return "\"" + lock.name() + "\" is running and you are sitting it, so ordinary mode is "
            + "closed to you until it ends. Sign in with your username and the examination "
            + "password on your slip.";
    }

    /**
     * Refuses an examination session outside the desktop app when its paper requires the app.
     *
     * <p>Only reached with the right examination password, so saying why is safe: the person
     * already holds the slip. See {@link com.cpintel.security.DesktopClient} for what the check
     * can and cannot establish.
     */
    private void requireDesktopAppIfAsked(com.cpintel.entity.GroupContest exam, User user,
                                          HttpServletRequest httpReq) {
        if (!Boolean.TRUE.equals(exam.getDesktopAppRequired())) return;
        if (com.cpintel.security.DesktopClient.isDesktop(httpReq)) return;
        auditService.record(user.getUserId(), AuditService.EXAM_SIGN_IN_NOT_DESKTOP, "EXAM",
            String.valueOf(exam.getContestId()), httpReq);
        throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, DESKTOP_APP_REQUIRED,
            "\"" + exam.getName() + "\" can only be sat in the CPIntel desktop app. Open the app "
                + "on this computer and sign in there with the same examination password. If you "
                + "are already in the app, update it from the download page.");
    }

    /** The error code for an examination session refused outside the desktop app. */
    public static final String DESKTOP_APP_REQUIRED = "DESKTOP_APP_REQUIRED";

    /** The error code for a sign-in or session refused because the holder's examination runs. */
    public static final String EXAM_IN_PROGRESS = "EXAM_IN_PROGRESS";

    @Transactional
    public void logout(String accessToken, Long userId) {
        if (StringUtils.hasText(accessToken)) {
            jwtService.blacklist(accessToken);
        }
        refreshTokenRepository.revokeAllByUserId(userId);
        // Signing out signs out every session, so the access tokens the others are holding stop
        // here too rather than working until they expire.
        jwtService.revokeUserTokens(userId);
        auditService.record(userId, AuditService.LOGOUT, "USER", String.valueOf(userId));
        log.info("User {} logged out", userId);
    }

    /**
     * The account somebody meant by what they typed.
     *
     * <p>An address is tried first, then a username, and nothing about the request says which
     * was intended — there is no mode switch on the sign-in box and no attempt to guess from
     * the shape of the string. An "@" is legal in neither direction here: usernames are
     * restricted to letters, digits and underscores, so an identifier containing one can only
     * be an address, and one without can only be a username. Trying both anyway keeps that
     * reasoning out of the security path, where it would silently become wrong the day the
     * username rules change.
     *
     * <p>Both columns are unique ignoring case, so there is no ambiguity to resolve: no address
     * can also be a username.
     */
    private User resolve(String identifier) {
        if (identifier.isEmpty()) return null;
        return userRepository.findByEmailIgnoreCase(identifier)
            .or(() -> userRepository.findByUsernameIgnoreCase(identifier))
            .orElse(null);
    }

    /** A hash of nothing anybody can type, compared against when no account matched. */
    private volatile String standInHash;

    @PostConstruct
    void prepareStandInHash() {
        standInHash();
    }

    private String standInHash() {
        String hash = standInHash;
        if (hash == null) {
            hash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
            standInHash = hash;
        }
        return hash;
    }

    /**
     * Trimmed and lower-cased, which is only the rate-limit key.
     *
     * The lookups themselves fold case in the query — see {@code findByEmailIgnoreCase} — so
     * this exists to stop {@code Ada@x.com} and {@code ada@x.com} counting as two separate
     * accounts' worth of guesses against the one account they both name.
     */
    private String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private AuthDto.AuthResponse buildAuthResponse(User user, HttpServletRequest req) {
        return buildAuthResponse(user, req, null);
    }

    private AuthDto.AuthResponse buildAuthResponse(User user, HttpServletRequest req,
                                                   com.cpintel.entity.GroupContest exam) {
        return buildAuthResponse(user, req, exam, java.util.UUID.randomUUID().toString(),
            jwtService.generateRefreshToken());
    }

    /**
     * Stores a new refresh token and answers with it.
     *
     * @param exam         the paper an examination session is for; null for an ordinary session
     * @param familyId     the sign-in this session was renewed from; a fresh one for a new
     *                     sign-in
     * @param refreshToken random for a new sign-in, derived from its predecessor on renewal
     */
    private AuthDto.AuthResponse buildAuthResponse(User user, HttpServletRequest req,
                                                   com.cpintel.entity.GroupContest exam,
                                                   String familyId, String refreshToken) {
        Long examId = exam == null ? null : exam.getContestId();

        // An examination session lives no longer than its paper.
        Instant expires = Instant.now().plusMillis(7 * 24 * 60 * 60 * 1000L);
        if (exam != null && exam.getEndsAt() != null) {
            Instant paperEnd = exam.getEndsAt().plus(com.cpintel.events.ExamLoginService.AFTER_END);
            if (paperEnd.isBefore(expires)) expires = paperEnd;
        }

        RefreshToken rt = RefreshToken.builder()
            .user(user)
            .token(com.cpintel.common.TokenDigest.of(refreshToken))
            .familyId(familyId)
            .examId(examId)
            .expiresAt(expires)
            .ipAddress(req.getRemoteAddr())
            .deviceInfo(req.getHeader("User-Agent"))
            .revoked(false)
            .build();
        refreshTokenRepository.save(rt);

        return respond(user, exam, refreshToken);
    }

    private AuthDto.AuthResponse respond(User user, com.cpintel.entity.GroupContest exam,
                                         String refreshToken) {
        Long examId = exam == null ? null : exam.getContestId();
        String accessToken  = jwtService.generateAccessToken(user.getUserId(), user.getEmail(),
            user.getRole(), examId);
        return AuthDto.AuthResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .user(userMapper.toProfile(user))
            .mode(exam == null ? "NORMAL" : "EXAM")
            .examId(examId)
            .build();
    }
}
