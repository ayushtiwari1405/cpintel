package com.cpintel.questions;

import com.cpintel.exception.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;

import java.io.IOException;

/**
 * Whether an uploaded file is a statement PDF CPIntel will hand to candidates.
 *
 * <p>It must open as a PDF, without a password, with between one and {@link #MAX_PAGES} pages,
 * and carry no JavaScript: a statement is something to read, and a script in it would run in
 * whatever viewer each candidate's browser uses.
 */
final class StatementPdfCheck {

    static final int MAX_PAGES = 50;

    private StatementPdfCheck() {}

    /** The page count of a PDF that passes; otherwise a 400 saying what is wrong. */
    static int pages(byte[] bytes) {
        if (bytes == null || bytes.length < 5 || !new String(bytes, 0, 5).equals("%PDF-")) {
            throw ApiException.badRequest("That file is not a PDF.");
        }
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            if (doc.isEncrypted()) {
                throw ApiException.badRequest("The PDF is password-protected or encrypted. "
                    + "Save it again without protection.");
            }
            int pages = doc.getNumberOfPages();
            if (pages < 1 || pages > MAX_PAGES) {
                throw ApiException.badRequest("A statement PDF can have 1 to " + MAX_PAGES
                    + " pages; this one has " + pages + ".");
            }
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            PDDocumentNameDictionary names = catalog.getNames();
            if ((names != null && names.getJavaScript() != null)
                || catalog.getOpenAction() instanceof PDActionJavaScript) {
                throw ApiException.badRequest("The PDF contains JavaScript. Export it again "
                    + "without scripts (\"Print to PDF\" usually does this).");
            }
            return pages;
        } catch (InvalidPasswordException e) {
            throw ApiException.badRequest("The PDF is password-protected or encrypted. "
                + "Save it again without protection.");
        } catch (IOException e) {
            throw ApiException.badRequest("That PDF could not be read. It may be damaged.");
        }
    }
}
