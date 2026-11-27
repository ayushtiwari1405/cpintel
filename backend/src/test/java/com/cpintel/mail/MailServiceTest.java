package com.cpintel.mail;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real message building, with only the network left out. */
class MailServiceTest {

    private final List<MimeMessage> sent = new ArrayList<>();

    private MailService service() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage message) {
                sent.add(message);
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> senders = mock(ObjectProvider.class);
        when(senders.getIfAvailable()).thenReturn(sender);
        MailService service = new MailService(senders);
        ReflectionTestUtils.setField(service, "host", "smtp.x.org");
        ReflectionTestUtils.setField(service, "from", "CPIntel <no-reply@x.org>");
        return service;
    }

    private static String written(MimeMessage message) throws Exception {
        message.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a notice with a text and an HTML body is sent with both")
    void sendsBothBodies() throws Exception {
        service().send("a@x.org", "Reset", "plain body", "<p>html body</p>");

        assertThat(sent).hasSize(1);
        assertThat(written(sent.get(0))).contains("plain body", "<p>html body</p>", "text/html");
    }

    @Test
    @DisplayName("a message somebody is waiting on is sent with both bodies too")
    void deliversBothBodies() throws Exception {
        service().deliver("a@x.org", "Sheet", "plain body", "<p>html body</p>");

        assertThat(sent).hasSize(1);
        assertThat(written(sent.get(0))).contains("plain body", "<p>html body</p>");
    }
}
