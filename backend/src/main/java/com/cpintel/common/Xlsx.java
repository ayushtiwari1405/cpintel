package com.cpintel.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a spreadsheet (.xlsx): a few named sheets of plain rows, the first row of each a bold
 * header that stays in view.
 *
 * <p>An .xlsx is a zip of a few XML files, and a table of names and numbers needs none of what
 * a spreadsheet library is for. This is the server's counterpart of the frontend's
 * {@code utils/spreadsheet.ts}, written directly for the same reason that one is.
 *
 * <p>A {@link Number} becomes a numeric cell, so a column of marks sorts and sums. Everything
 * else is written as an inline string, exactly as given: a username such as {@code 0012} keeps
 * its zeros, and one beginning with {@code =} is text rather than a formula.
 */
public final class Xlsx {

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String RELS =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE_RELS =
        "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String TYPES = "application/vnd.openxmlformats-officedocument.spreadsheetml.";

    public static final String CONTENT_TYPE = TYPES + "sheet";

    private record Sheet(String name, List<? extends List<?>> rows) {}

    private final List<Sheet> sheets = new ArrayList<>();

    /** Adds a sheet. The first row is its header. */
    public Xlsx sheet(String name, List<? extends List<?>> rows) {
        sheets.add(new Sheet(sheetName(name), rows));
        return this;
    }

    public byte[] toBytes() {
        if (sheets.isEmpty()) throw new IllegalStateException("A workbook needs a sheet.");

        StringBuilder types = new StringBuilder(XML)
            .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"").append(TYPES).append("sheet.main+xml\"/>")
            .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"").append(TYPES).append("styles+xml\"/>");
        StringBuilder workbook = new StringBuilder(XML)
            .append("<workbook xmlns=\"").append(MAIN).append("\" xmlns:r=\"").append(RELS).append("\">")
            .append("<bookViews><workbookView/></bookViews><sheets>");
        StringBuilder rels = new StringBuilder(XML)
            .append("<Relationships xmlns=\"").append(PACKAGE_RELS).append("\">");

        for (int i = 1; i <= sheets.size(); i++) {
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                .append(".xml\" ContentType=\"").append(TYPES).append("worksheet+xml\"/>");
            workbook.append("<sheet name=\"").append(escape(sheets.get(i - 1).name()))
                .append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(RELS)
                .append("/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        types.append("</Types>");
        workbook.append("</sheets></workbook>");
        rels.append("<Relationship Id=\"rId").append(sheets.size() + 1).append("\" Type=\"")
            .append(RELS).append("/styles\" Target=\"styles.xml\"/></Relationships>");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", types.toString());
            put(zip, "_rels/.rels", XML + "<Relationships xmlns=\"" + PACKAGE_RELS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + RELS + "/officeDocument\" "
                + "Target=\"xl/workbook.xml\"/></Relationships>");
            put(zip, "xl/workbook.xml", workbook.toString());
            put(zip, "xl/_rels/workbook.xml.rels", rels.toString());
            put(zip, "xl/styles.xml", STYLES);
            for (int i = 1; i <= sheets.size(); i++) {
                put(zip, "xl/worksheets/sheet" + i + ".xml", sheetXml(sheets.get(i - 1).rows()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Two cell styles: plain, and bold for the header row. */
    private static final String STYLES = XML
        + "<styleSheet xmlns=\"" + MAIN + "\">"
        + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
        + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
        + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>"
        + "<fill><patternFill patternType=\"gray125\"/></fill></fills>"
        + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
        + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
        + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
        + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
        + "</styleSheet>";

    private static String sheetXml(List<? extends List<?>> rows) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[columns];

        StringBuilder data = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<?> row = rows.get(r);
            data.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < row.size(); c++) {
                Object value = row.get(c);
                if (value == null) continue;
                String ref = column(c) + (r + 1);
                String style = r == 0 ? " s=\"1\"" : "";
                if (value instanceof Number number && finite(number)) {
                    data.append("<c r=\"").append(ref).append('"').append(style).append("><v>")
                        .append(numeric(number)).append("</v></c>");
                    widths[c] = Math.max(widths[c], numeric(number).length());
                } else {
                    String text = value.toString();
                    data.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style)
                        .append("><is><t xml:space=\"preserve\">").append(escape(text))
                        .append("</t></is></c>");
                    widths[c] = Math.max(widths[c], text.length());
                }
            }
            data.append("</row>");
        }

        StringBuilder xml = new StringBuilder(XML)
            .append("<worksheet xmlns=\"").append(MAIN).append("\">")
            .append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" ")
            .append("topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
            .append("</sheetView></sheetViews>");
        if (columns > 0) {
            xml.append("<cols>");
            for (int c = 0; c < columns; c++) {
                xml.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1)
                    .append("\" width=\"").append(Math.min(Math.max(widths[c], 8) + 2, 60))
                    .append("\" customWidth=\"1\"/>");
            }
            xml.append("</cols>");
        }
        return xml.append("<sheetData>").append(data).append("</sheetData></worksheet>").toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static boolean finite(Number number) {
        double value = number.doubleValue();
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /** 30 rather than 30.0: a whole number of marks is written as one. */
    private static String numeric(Number number) {
        double value = number.doubleValue();
        return value == Math.rint(value) && Math.abs(value) < 1e15
            ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** A, B … Z, AA, AB … */
    private static String column(int index) {
        StringBuilder name = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) {
            name.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return name.toString();
    }

    /** A sheet name may not be empty, longer than 31 characters, or hold any of these. */
    private static String sheetName(String name) {
        String clean = name == null ? "" : name.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (clean.isEmpty()) clean = "Sheet";
        return clean.length() > 31 ? clean.substring(0, 31) : clean;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        value.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    // Characters XML 1.0 cannot carry at all are dropped.
                    boolean legal = c == 0x9 || c == 0xA || c == 0xD
                        || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)
                        || (c >= 0x10000 && c <= 0x10FFFF);
                    if (legal) out.appendCodePoint(c);
                }
            }
        });
        return out.toString();
    }
}
