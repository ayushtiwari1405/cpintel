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
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Mailing people the passwords an admin has just handed out, when the admin asks for it.
 *
 * <p>Nothing in CPIntel mails a password by itself. This runs only from the password sheet
 * editor, and only for a password that is still the one that was issued:
 *
 * <ul>
 *   <li>an account's, while its owner has not set one of their own — so this can never be used
 *       to test guesses at a password somebody chose;</li>
 *   <li>a candidate's sign-in password for an examination they are still assigned to.</li>
 * </ul>
 *
 * <p>Sending is synchronous, one message at a time, so the admin is told which went and which
 * did not. Nothing is queued or stored: a password is held only while its request runs. There is
 * no transaction here, so no database connection is held while the mail server answers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordMailService {

    private final MailService mail;
    private final AdminUserService adminUsers;
    private final ExamPasswordService examPasswords;
    private final EventService eventService;
    private final UserRepository users;
    private final GroupContestRepository events;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public PasswordMailDto.Status status() {
        boolean configured = mail.isConfigured();
        return new PasswordMailDto.Status(configured, configured ? mail.from() : null);
    }

    /** Account passwords: new accounts, passwords an admin set, rosters and teaching assistants. */
    public List<PasswordMailDto.Result> toAccounts(Long adminId, PasswordMailDto.Request req,
                                                   boolean callerIsSuperAdmin,
                                                   HttpServletRequest httpReq) {
        return sendEach(req, message -> {
            User user = adminUsers.requireMayEmail(adminId, message.userId(), callerIsSuperAdmin);
            requireActive(user);
            if (user.getPasswordChangedAt() != null) {
                throw ApiException.badRequest("They have since set a password of their own.");
            }
            if (!passwordEncoder.matches(message.password(), user.getPasswordHash())) {
                throw ApiException.badRequest("That is no longer their password.");
            }
            return user;
        }, user -> auditService.record(adminId, AuditService.PASSWORD_MAILED, "USER",
            String.valueOf(user.getUserId()), httpReq));
    }

    /** Examination sign-in passwords. The caller has been checked against the examination. */
    public List<PasswordMailDto.Result> toCandidates(Long adminId, Long eventId,
                                                     PasswordMailDto.Request req,
                                                     HttpServletRequest httpReq) {
        GroupContest exam = events.findById(eventId)
            .orElseThrow(() -> ApiException.notFound("No such examination"));
        // As issuing reads it, which counts a draft's candidates: slips go out before publishing.
        Set<Long> assigned = eventService.participantIds(eventId);
        return sendEach(req, message -> {
            if (!assigned.contains(message.userId())
                || !examPasswords.isCurrentPasscode(eventId, message.userId(), message.password())) {
                throw ApiException.badRequest("That is no longer their password for this "
                    + "examination, or they are no longer assigned to it.");
            }
            User user = users.findById(message.userId())
                .orElseThrow(() -> ApiException.notFound("No such user"));
            requireActive(user);
            return user;
        }, user -> auditService.recordIn(exam.getClassroomId(), adminId,
            AuditService.EXAM_PASSCODE_MAILED, "EXAM", eventId + ":user=" + user.getUserId(),
            httpReq));
    }

    private List<PasswordMailDto.Result> sendEach(
        PasswordMailDto.Request req,
        Function<PasswordMailDto.Message, User> recipient,
        Consumer<User> sent) {
        if (!mail.isConfigured()) {
            throw ApiException.badRequest("Email is not set up on this server. Set SMTP_HOST and "
                + "restart, or hand out the PDF instead.");
        }
        List<PasswordMailDto.Result> results = new ArrayList<>();
        for (PasswordMailDto.Message message : req.messages()) {
            User user;
            try {
                user = recipient.apply(message);
            } catch (ApiException e) {
                results.add(new PasswordMailDto.Result(message.userId(), false, e.getMessage()));
                continue;
            }
            try {
                mail.deliver(user.getEmail(), message.subject(), plain(message), html(message));
            } catch (Exception e) {
                // The kind of failure only: the exception's text can quote the address.
                log.warn("Could not mail a password to {}: {}",
                    MailService.masked(user.getEmail()), e.getClass().getSimpleName());
                results.add(new PasswordMailDto.Result(message.userId(), false,
                    e instanceof MailAuthenticationException
                        ? "The mail server refused CPIntel's sign-in."
                        : "The mail server did not accept it."));
                continue;
            }
            sent.accept(user);
            results.add(new PasswordMailDto.Result(message.userId(), true, null));
        }
        return results;
    }

    private static void requireActive(User user) {
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw ApiException.badRequest("This account is deactivated.");
        }
    }

    static String plain(PasswordMailDto.Message message) {
        StringBuilder out = new StringBuilder();
        for (PasswordMailDto.Line line : message.lines()) {
            line.spans().forEach(span -> out.append(span.text()));
            out.append('\n');
        }
        return out.toString();
    }

    static String html(PasswordMailDto.Message message) {
        StringBuilder out = new StringBuilder("<!doctype html><html><body style=\""
            + "font-family:Arial,Helvetica,sans-serif;font-size:14px;line-height:1.5;"
            + "color:#111\">");
        for (PasswordMailDto.Line line : message.lines()) {
            out.append("<div style=\"white-space:pre-wrap")
                .append(line.heading() ? ";font-size:19px;font-weight:bold" : "")
                .append("\">");
            if (line.spans().stream().allMatch(span -> span.text().isEmpty())) out.append("<br>");
            for (PasswordMailDto.Span span : line.spans()) {
                String text = HtmlUtils.htmlEscape(span.text(), "UTF-8");
                switch (span.style()) {
                    case BOLD -> out.append("<strong>").append(text).append("</strong>");
                    case PASSWORD -> out.append("<code style=\"font-family:'Courier New',"
                        + "monospace;font-size:16px;font-weight:bold\">").append(text)
                        .append("</code>");
                    default -> out.append(text);
                }
            }
            out.append("</div>");
        }
        return out.append("</body></html>").toString();
    }
}
