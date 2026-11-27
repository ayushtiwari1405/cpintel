package com.cpintel.mail;

import com.cpintel.admin.AdminUserService;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.events.EventService;
import com.cpintel.events.ExamPasswordService;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PasswordMailServiceTest {

    private final MailService mail = mock(MailService.class);
    private final AdminUserService adminUsers = mock(AdminUserService.class);
    private final ExamPasswordService examPasswords = mock(ExamPasswordService.class);
    private final EventService eventService = mock(EventService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final GroupContestRepository events = mock(GroupContestRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuditService audit = mock(AuditService.class);
    private final PasswordMailService service = new PasswordMailService(
        mail, adminUsers, examPasswords, eventService, users, events, encoder, audit);

    private final User asha = User.builder().userId(7L).username("asha")
        .email("asha@x.org").passwordHash("hash").isActive(true).build();

    @BeforeEach
    void configured() {
        when(mail.isConfigured()).thenReturn(true);
        when(adminUsers.requireMayEmail(1L, 7L, false)).thenReturn(asha);
        when(encoder.matches("issued-pass", "hash")).thenReturn(true);
    }

    private static PasswordMailDto.Message message(long userId, String password) {
        return new PasswordMailDto.Message(userId, password, "Your account", List.of(
            new PasswordMailDto.Line(true, List.of(span("Hello", PasswordMailDto.Style.REGULAR))),
            new PasswordMailDto.Line(false, List.of()),
            new PasswordMailDto.Line(false, List.of(
                span("<b> & ", PasswordMailDto.Style.BOLD),
                span(password, PasswordMailDto.Style.PASSWORD)))));
    }

    private static PasswordMailDto.Span span(String text, PasswordMailDto.Style style) {
        return new PasswordMailDto.Span(text, style);
    }

    private static PasswordMailDto.Request request(PasswordMailDto.Message... messages) {
        return new PasswordMailDto.Request(Arrays.asList(messages));
    }

    @Test
    @DisplayName("an issued password goes to the address on the account, escaped, and is audited")
    void sendsToTheAccount() throws Exception {
        var results = service.toAccounts(1L, request(message(7L, "issued-pass")), false, null);

        assertThat(results).containsExactly(new PasswordMailDto.Result(7L, true, null));
        ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(mail).deliver(eq("asha@x.org"), eq("Your account"), plain.capture(), html.capture());
        assertThat(plain.getValue()).isEqualTo("Hello\n\n<b> & issued-pass\n");
        assertThat(html.getValue()).contains("<strong>&lt;b&gt; &amp; </strong>")
            .contains(">issued-pass</code>").doesNotContain("<b>");
        verify(audit).record(1L, AuditService.PASSWORD_MAILED, "USER", "7", null);
    }

    @Test
    @DisplayName("a password its owner chose is never tested or sent")
    void ownPasswordIsLeftAlone() throws Exception {
        asha.setPasswordChangedAt(Instant.now());

        var results = service.toAccounts(1L, request(message(7L, "a guess")), false, null);

        assertThat(results.get(0).sent()).isFalse();
        assertThat(results.get(0).error()).contains("set a password of their own");
        verify(encoder, never()).matches(any(), any());
        verify(mail, never()).deliver(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a stale password, a deactivated account and a refused one are each reported")
    void refusals() throws Exception {
        User off = User.builder().userId(8L).email("off@x.org").isActive(false).build();
        when(adminUsers.requireMayEmail(1L, 8L, false)).thenReturn(off);
        when(adminUsers.requireMayEmail(1L, 9L, false))
            .thenThrow(ApiException.forbidden("Not yours."));

        var results = service.toAccounts(1L, request(message(7L, "old-pass"),
            message(8L, "x"), message(9L, "x")), false, null);

        assertThat(results).extracting(PasswordMailDto.Result::error).containsExactly(
            "That is no longer their password.", "This account is deactivated.", "Not yours.");
        verify(mail, never()).deliver(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("one failed delivery does not stop the next, and is not audited as sent")
    void deliveryFailure() throws Exception {
        doThrow(new MailSendException("550 asha@x.org")).doNothing()
            .when(mail).deliver(anyString(), anyString(), anyString(), anyString());

        var results = service.toAccounts(1L,
            request(message(7L, "issued-pass"), message(7L, "issued-pass")), false, null);

        assertThat(results).extracting(PasswordMailDto.Result::sent).containsExactly(false, true);
        assertThat(results.get(0).error()).doesNotContain("asha@x.org");
        verify(audit, times(1)).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("with no mail server nothing is attempted")
    void notConfigured() {
        when(mail.isConfigured()).thenReturn(false);

        assertThatThrownBy(() ->
            service.toAccounts(1L, request(message(7L, "issued-pass")), false, null))
            .isInstanceOf(ApiException.class).hasMessageContaining("SMTP_HOST");
        verifyNoInteractions(adminUsers);
    }

    @Test
    @DisplayName("an examination password needs an assigned candidate and their present code")
    void candidates() throws Exception {
        GroupContest exam = GroupContest.builder().contestId(3L).classroomId(2L).build();
        when(events.findById(3L)).thenReturn(Optional.of(exam));
        when(eventService.participantIds(3L)).thenReturn(Set.of(7L));
        when(examPasswords.isCurrentPasscode(3L, 7L, "CODE-1")).thenReturn(true);
        when(examPasswords.isCurrentPasscode(3L, 8L, "CODE-2")).thenReturn(true);
        when(users.findById(7L)).thenReturn(Optional.of(asha));

        var results = service.toCandidates(1L, 3L, request(message(7L, "CODE-1"),
            message(7L, "OLD-CODE"), message(8L, "CODE-2")), null);

        assertThat(results).extracting(PasswordMailDto.Result::sent)
            .containsExactly(true, false, false);
        verify(mail, times(1)).deliver(eq("asha@x.org"), anyString(), anyString(), anyString());
        verify(audit).recordIn(2L, 1L, AuditService.EXAM_PASSCODE_MAILED, "EXAM", "3:user=7", null);
    }

    @Test
    @DisplayName("neither the password nor the text is in what a log line would show")
    void nothingInToString() {
        assertThat(request(message(7L, "issued-pass")).toString()).doesNotContain("issued-pass");
    }
}
