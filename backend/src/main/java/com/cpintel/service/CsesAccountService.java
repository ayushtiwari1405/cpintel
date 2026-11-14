package com.cpintel.service;

import com.cpintel.common.SecretBox;
import com.cpintel.dto.PlatformDto;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.cses.CsesClient;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.repository.jpa.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Connecting a CSES account through CSES's own API sign-in.
 *
 * <p>CPIntel asks cses.fi for a token and sends the student to the address that comes with it;
 * signing in there approves the token for their account. CPIntel then asks CSES whose token it
 * is, which is proof of the account without any code to paste, so a connected CSES handle is
 * verified. The token is kept sealed (with the Codeforces session key) and is what lab checks
 * read and what Submit sends with. Disconnecting signs the token out on CSES too.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsesAccountService {

    /** How long a sign-in may wait for the student to approve it on cses.fi. */
    static final Duration PENDING_FOR = Duration.ofMinutes(15);
    private static final String PENDING = "cses-login:";

    private final CsesClient cses;
    private final PlatformAccountRepository accounts;
    private final UserRepository users;
    private final UserMapper mapper;
    private final RedisTemplate<String, Object> redis;

    @Value("${cpintel.practice.session-key:}")
    private String sessionKey;

    private volatile SecretBox box;

    public record Started(String authenticationUrl, Instant expiresAt) {}

    public record Checked(boolean connected, PlatformDto.Summary account) {}

    public Started start(Long userId) {
        SecretBox sealer = box();
        CsesClient.Login login;
        try {
            login = cses.startLogin();
        } catch (Exception e) {
            log.warn("CSES login start failed: {}", e.getMessage());
            throw ApiException.serviceUnavailable("CSES did not answer. Try again in a minute.");
        }
        redis.opsForValue().set(PENDING + userId, sealer.seal(login.token()), PENDING_FOR);
        return new Started(login.authenticationUrl(), Instant.now().plus(PENDING_FOR));
    }

    @Transactional
    public Checked check(Long userId) {
        Object sealed = redis.opsForValue().get(PENDING + userId);
        if (sealed == null) {
            throw ApiException.badRequest("That CSES sign-in has expired. Start again.");
        }
        String token = box().open(sealed.toString());
        CsesClient.User who;
        try {
            who = cses.whoAmI(token);
        } catch (Exception e) {
            throw ApiException.serviceUnavailable("CSES did not answer. Try again in a minute.");
        }
        if (who == null) return new Checked(false, null);

        if (accounts.existsByPlatformAndHandleIgnoreCaseAndUserUserIdNot("CSES", who.username(), userId)) {
            cses.logout(token);
            redis.delete(PENDING + userId);
            throw ApiException.conflict("That CSES account is already connected to another CPIntel account.");
        }
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));
        PlatformAccount account = accounts.findByUserUserIdAndPlatform(userId, "CSES").orElse(null);
        if (account != null && account.getAccessToken() != null) {
            cses.logout(box().open(account.getAccessToken()));
        }
        if (account == null) {
            account = PlatformAccount.builder().user(user).platform("CSES").isActive(true).build();
        }
        account.setHandle(who.username());
        account.setAccessToken(box().seal(token));
        account.setVerifiedAt(Instant.now());
        account.setSyncStatus("COMPLETED");
        account = accounts.save(account);
        redis.delete(PENDING + userId);
        log.info("Connected CSES account '{}' for user {}", who.username(), userId);
        return new Checked(true, mapper.toPlatformSummary(account));
    }

    @Transactional
    public void disconnect(Long userId) {
        PlatformAccount account = accounts.findByUserUserIdAndPlatform(userId, "CSES")
            .orElseThrow(() -> ApiException.notFound("No CSES account connected."));
        if (account.getAccessToken() != null) cses.logout(box().open(account.getAccessToken()));
        accounts.delete(account);
    }

    /** The account's live token, or a refusal telling the student to connect (again). */
    public String token(PlatformAccount account) {
        if (account == null || account.getAccessToken() == null) {
            throw ApiException.badRequest("Connect your CSES account on the Platforms page first.");
        }
        return box().open(account.getAccessToken());
    }

    public String tokenOf(Long userId) {
        return token(accounts.findByUserUserIdAndPlatform(userId, "CSES").orElse(null));
    }

    /** CSES stopped accepting the token: shown as needing to reconnect. */
    @Transactional
    public void markRejected(PlatformAccount account) {
        accounts.updateSyncStatus(account.getAccountId(), "FAILED");
    }

    private SecretBox box() {
        SecretBox current = box;
        if (current == null) {
            if (sessionKey == null || sessionKey.isBlank()) {
                throw ApiException.serviceUnavailable(
                    "CSES is not set up on this server: CPINTEL_SESSION_KEY is missing.");
            }
            current = new SecretBox(sessionKey);
            box = current;
        }
        return current;
    }
}
