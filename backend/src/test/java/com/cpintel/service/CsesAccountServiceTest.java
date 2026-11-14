package com.cpintel.service;

import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.cses.CsesClient;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.repository.jpa.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Connecting a CSES account through CSES's own sign-in. */
class CsesAccountServiceTest {

    private CsesClient cses;
    private PlatformAccountRepository accounts;
    private CsesAccountService service;
    private final Map<String, Object> store = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cses = mock(CsesClient.class);
        accounts = mock(PlatformAccountRepository.class);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().userId(7L).username("alice").build()));
        when(accounts.save(any())).thenAnswer(c -> c.getArgument(0));
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        doAnswer(c -> store.put(c.getArgument(0), c.getArgument(1))).when(ops).set(anyString(), any(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(c -> store.get(c.<String>getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(c -> store.remove(c.<String>getArgument(0)) != null);
        service = new CsesAccountService(cses, accounts, users, new UserMapper(), redis);
        ReflectionTestUtils.setField(service, "sessionKey", "test-session-key");
    }

    @Test
    void connectsOnceTheSignInIsApprovedAndKeepsOnlyASealedToken() {
        when(cses.startLogin()).thenReturn(new CsesClient.Login("tok-123", "https://cses.fi/api/auth/abc"));
        assertThat(service.start(7L).authenticationUrl()).isEqualTo("https://cses.fi/api/auth/abc");
        assertThat(store.values().iterator().next().toString()).doesNotContain("tok-123");

        // Not approved yet.
        when(cses.whoAmI("tok-123")).thenReturn(null);
        assertThat(service.check(7L).connected()).isFalse();

        when(cses.whoAmI("tok-123")).thenReturn(new CsesClient.User(42, "alice_cses", null));
        var checked = service.check(7L);
        assertThat(checked.connected()).isTrue();
        assertThat(checked.account().getHandle()).isEqualTo("alice_cses");
        assertThat(checked.account().getVerifiedAt()).isNotNull();
        verify(accounts).save(argThat(a -> a.getPlatform().equals("CSES") && a.getAccessToken() != null
            && !a.getAccessToken().contains("tok-123")));
        assertThat(store).isEmpty();
    }

    @Test
    void refusesAnAccountAlreadyConnectedElsewhere() {
        when(cses.startLogin()).thenReturn(new CsesClient.Login("tok-9", "u"));
        service.start(7L);
        when(cses.whoAmI("tok-9")).thenReturn(new CsesClient.User(1, "bob", null));
        when(accounts.existsByPlatformAndHandleIgnoreCaseAndUserUserIdNot("CSES", "bob", 7L)).thenReturn(true);
        assertThatThrownBy(() -> service.check(7L)).isInstanceOf(ApiException.class).hasMessageContaining("already connected");
        verify(cses).logout("tok-9");
    }

    @Test
    void anExpiredSignInHasToBeStartedAgain() {
        assertThatThrownBy(() -> service.check(7L)).isInstanceOf(ApiException.class).hasMessageContaining("expired");
    }

    @Test
    void theStoredTokenOpensForUse() {
        when(cses.startLogin()).thenReturn(new CsesClient.Login("tok-5", "u"));
        service.start(7L);
        when(cses.whoAmI("tok-5")).thenReturn(new CsesClient.User(5, "carol", null));
        service.check(7L);
        var saved = org.mockito.ArgumentCaptor.forClass(PlatformAccount.class);
        verify(accounts).save(saved.capture());
        assertThat(service.token(saved.getValue())).isEqualTo("tok-5");
    }
}
