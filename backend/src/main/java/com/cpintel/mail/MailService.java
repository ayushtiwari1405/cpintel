package com.cpintel.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.mail.internet.MimeMessage;

/**
 * The one place an email leaves this system.
 *
 * <p><b>It never throws at a caller.</b> Every send here is a notification about something that
 * has already happened — a password was reset, an account was created — and none of them is the
 * thing the user asked for. A mail server that is down, misconfigured or slow must not turn a
 * successful password change into a 500 that makes somebody believe it failed and try again.
 * So a failed send is logged and swallowed, and the caller's own audit record is what says the
 * action happened.
 *
 * <p><b>It works with no mail server at all.</b> A deployment that has not set {@code
 * SMTP_HOST} logs that a message was not delivered, with the subject and the recipient masked,
 * and nothing else: the body of a reset message is a live link that signs in as its recipient,
 * and logs are read by more people than should hold one. A closed exam-lab network, where there
 * is no SMTP to point at, can set {@code CPINTEL_MAIL_LOG_UNDELIVERED=true} to have the whole
 * message - recipient and link included - written at INFO, so an operator can finish a reset by
 * hand. Refusing to start, or refusing password resets, would make those deployments worse
 * rather than safer. {@link com.cpintel.config.SecretsCheck} warns at startup either way, so it
 * is a visible choice rather than a silent one.
 *
 * <p><b>Nothing here sends an examination password.</b> Those are carried into the room on
 * paper; see {@link com.cpintel.events.ExamPasswordService} for why.
 */
@Service
@Slf4j
public class MailService {

    private final ObjectProvider<JavaMailSender> senders;

    @Value("${cpintel.mail.from:CPIntel <no-reply@cpintel.local>}")
    private String from;

    @Value("${spring.mail.host:}")
    private String host;

    /**
     * Whether a message with no SMTP to go out through is written to the log whole. Read as a
     * string so that anything but "true" - a typo included - leaves it off instead of failing
     * startup or, worse, turning it on.
     */
    @Value("${cpintel.mail.log-undelivered:false}")
    private String logUndelivered;

    /** Where a link in an email should point — the address people actually open CPIntel at. */
    @Value("${cpintel.mail.base-url:http://localhost:5173}")
    private String baseUrl;

    public MailService(ObjectProvider<JavaMailSender> senders) {
        this.senders = senders;
    }

    /** True when an operator has asked for undelivered messages, links included, in the log. */
    public boolean logsUndelivered() {
        return logUndelivered != null && "true".equalsIgnoreCase(logUndelivered.trim());
    }

    /** True when an operator has pointed this deployment at an SMTP server. */
    public boolean isConfigured() {
        return StringUtils.hasText(host) && senders.getIfAvailable() != null;
    }

    public String baseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Sends one message, or records that it could not be sent.
     *
     * Asynchronous because the caller is inside a request the user is waiting on, and an SMTP
     * handshake to a server on the other side of a firewall can take the better part of a
     * connection timeout. Nothing downstream reads the result, which is what makes that safe.
     */
    @Async("mailExecutor")
    public void send(String to, String subject, String plainText, String html) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || !StringUtils.hasText(host)) {
            if (logsUndelivered()) {
                // Deliberately the whole body, not a summary: an operator who turned this on
                // finishes what the user started from this line.
                log.info("No SMTP configured — not sending mail to {}.\nSubject: {}\n{}",
                    to, subject, plainText);
            } else {
                log.info("No SMTP configured — \"{}\" to {} was not sent. Set "
                    + "CPINTEL_MAIL_LOG_UNDELIVERED=true to write such messages to this log.",
                    subject, masked(to));
            }
            return;
        }

        try {
            if (StringUtils.hasText(html)) {
                MimeMessage message = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
                helper.setFrom(from);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(plainText, html);
                sender.send(message);
            } else {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(to);
                message.setSubject(subject);
                message.setText(plainText);
                sender.send(message);
            }
            log.info("Sent \"{}\" to {}", subject, masked(to));
        } catch (Exception e) {
            // Neither the body, which can carry a reset link, nor the whole address.
            log.warn("Could not send \"{}\" to {}: {}", subject, masked(to), e.getMessage());
        }
    }

    /**
     * An address with all but its first character hidden, keeping the domain - enough to tell
     * a delivery problem at one mail host from a typo, without writing the address into a log.
     */
    public static String masked(String address) {
        if (address == null) return "(none)";
        int at = address.lastIndexOf('@');
        if (at < 1) return "***";
        return address.charAt(0) + "***" + address.substring(at);
    }

    public void send(String to, String subject, String plainText) {
        send(to, subject, plainText, null);
    }
}
