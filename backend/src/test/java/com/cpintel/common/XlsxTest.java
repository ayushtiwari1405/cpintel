package com.cpintel.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The workbook is a zip of XML that a spreadsheet program will open: every part well formed. */
class XlsxTest {

    static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        return files;
    }

    private static Document parse(byte[] xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml));
    }

    @Test
    @DisplayName("Every sheet is a part of the workbook, and every part is well-formed XML")
    void parts() throws Exception {
        byte[] bytes = new Xlsx()
            .sheet("Leaderboard", List.of(List.of("Rank", "Username"), List.of(1, "asha")))
            .sheet("About", List.of(List.of("Detail", "Value")))
            .toBytes();

        Map<String, byte[]> files = unzip(bytes);
        assertTrue(files.keySet().containsAll(List.of("[Content_Types].xml", "_rels/.rels",
            "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
            "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")));
        for (byte[] file : files.values()) parse(file);

        NodeList sheets = parse(files.get("xl/workbook.xml")).getElementsByTagName("sheet");
        assertEquals(2, sheets.getLength());
        assertEquals("Leaderboard", sheets.item(0).getAttributes().getNamedItem("name").getNodeValue());
    }

    @Test
    @DisplayName("A number is a numeric cell; text stays text, whatever it looks like")
    void cellTypes() throws Exception {
        byte[] bytes = new Xlsx().sheet("S", List.of(
            List.of("Marks", "Username"),
            Arrays.asList(30.0, "0012"),
            Arrays.asList(12.5, "=SUM(A1) <b> & \u0001co"),
            Arrays.asList(null, "x"))).toBytes();

        String sheet = new String(unzip(bytes).get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        assertTrue(sheet.contains("<c r=\"A2\"><v>30</v></c>"));
        assertTrue(sheet.contains("<c r=\"A3\"><v>12.5</v></c>"));
        assertTrue(sheet.contains("<c r=\"B2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">0012</t>"));
        assertTrue(sheet.contains("=SUM(A1) &lt;b&gt; &amp; co"));
        // An empty cell is left out rather than written as an empty string.
        assertFalse(sheet.contains("r=\"A4\""));
        // The header row is bold.
        assertTrue(sheet.contains("<c r=\"A1\" t=\"inlineStr\" s=\"1\">"));
    }

    @Test
    @DisplayName("A sheet name is made one a workbook will accept")
    void sheetNames() throws Exception {
        byte[] bytes = new Xlsx()
            .sheet("Round 1: A/B [final] and a very long name indeed", List.of(List.of("x")))
            .toBytes();

        String name = parse(unzip(bytes).get("xl/workbook.xml")).getElementsByTagName("sheet")
            .item(0).getAttributes().getNamedItem("name").getNodeValue();
        assertTrue(name.length() <= 31);
        assertFalse(name.matches(".*[\\[\\]:*?/\\\\].*"));
    }
}
