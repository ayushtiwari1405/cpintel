package com.cpintel.questions;

import com.cpintel.exception.ApiException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Which uploaded files are accepted as a statement PDF. */
class StatementPdfCheckTest {

    private static byte[] pdf(int pages, Consumer<PDDocument> change) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            change.accept(doc);
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String refusal(byte[] bytes) {
        return assertThrows(ApiException.class, () -> StatementPdfCheck.pages(bytes)).getMessage();
    }

    @Test
    @DisplayName("A plain PDF is accepted and its pages counted")
    void plain() throws Exception {
        assertEquals(3, StatementPdfCheck.pages(pdf(3, d -> {})));
    }

    @Test
    @DisplayName("Not a PDF, too many pages, a script, or a password are each refused with the reason")
    void refused() throws Exception {
        assertTrue(refusal("not a pdf".getBytes()).contains("not a PDF"));
        assertTrue(refusal("%PDF-1.7 garbage".getBytes()).contains("could not be read"));
        assertTrue(refusal(pdf(StatementPdfCheck.MAX_PAGES + 1, d -> {})).contains("pages"));
        assertTrue(refusal(pdf(1, d -> d.getDocumentCatalog()
            .setOpenAction(new PDActionJavaScript("app.alert(1)")))).contains("JavaScript"));
        assertTrue(refusal(pdf(1, d -> {
            try {
                d.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).contains("password"));
    }
}
