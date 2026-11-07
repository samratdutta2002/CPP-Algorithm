import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * XlsxWriter — writes a single-sheet .xlsx workbook using nothing but the
 * JDK (java.util.zip). No Apache POI / external dependency required, which
 * keeps this project buildable offline.
 *
 * Supports String, Integer, Long and Double cell values; everything else is
 * written via String.valueOf(). The header row is bold with a light-grey
 * fill and frozen (first row stays visible when scrolling).
 */
public class XlsxWriter {

    public static void write(File outFile, String sheetName, String[] headers, List<Object[]> rows)
            throws IOException {

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outFile))) {

            writeEntry(zos, "[Content_Types].xml", contentTypesXml());
            writeEntry(zos, "_rels/.rels", relsXml());
            writeEntry(zos, "xl/workbook.xml", workbookXml(safeSheetName(sheetName)));
            writeEntry(zos, "xl/_rels/workbook.xml.rels", workbookRelsXml());
            writeEntry(zos, "xl/styles.xml", stylesXml());
            writeEntry(zos, "xl/worksheets/sheet1.xml", sheetXml(headers, rows));
        }
    }

    private static String safeSheetName(String name) {
        if (name == null || name.isBlank()) return "Sheet1";
        String s = name.replaceAll("[\\[\\]\\*/\\\\?:]", " ").trim();
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    private static void writeEntry(ZipOutputStream zos, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zos.putNextEntry(entry);
        Writer w = new OutputStreamWriter(zos, StandardCharsets.UTF_8);
        w.write(content);
        w.flush();
        zos.closeEntry();
    }

    private static String contentTypesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
            + "</Types>";
    }

    private static String relsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>";
    }

    private static String workbookXml(String sheetName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + "<sheets><sheet name=\"" + escapeXml(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
            + "</workbook>";
    }

    private static String workbookRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "</Relationships>";
    }

    private static String stylesXml() {
        // s="0" -> default. s="1" -> bold header w/ light grey fill.
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"2\">"
            + "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "</fonts>"
            + "<fills count=\"3\">"
            + "<fill><patternFill patternType=\"none\"/></fill>"
            + "<fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9E1F2\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "</fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"2\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
            + "</cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";
    }

    private static String sheetXml(String[] headers, List<Object[]> rows) {
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sb.append("<sheetViews><sheetView tabSelected=\"1\" workbookViewId=\"0\">")
          .append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
          .append("</sheetView></sheetViews>");

        sb.append("<cols>");
        for (int c = 0; c < headers.length; c++) {
            sb.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1)
              .append("\" width=\"18\" customWidth=\"1\"/>");
        }
        sb.append("</cols>");

        sb.append("<sheetData>");

        // Header row
        sb.append("<row r=\"1\">");
        for (int c = 0; c < headers.length; c++) {
            appendCell(sb, c, 1, headers[c], 1);
        }
        sb.append("</row>");

        // Data rows
        for (int r = 0; r < rows.size(); r++) {
            int rowNum = r + 2;
            Object[] row = rows.get(r);
            sb.append("<row r=\"").append(rowNum).append("\">");
            for (int c = 0; c < row.length; c++) {
                appendCell(sb, c, rowNum, row[c], 0);
            }
            sb.append("</row>");
        }

        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private static void appendCell(StringBuilder sb, int colIdx, int rowNum, Object value, int styleIdx) {
        String ref = colLetter(colIdx) + rowNum;
        if (value == null) {
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(styleIdx).append("\"/>");
            return;
        }
        if (value instanceof Integer || value instanceof Long) {
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(styleIdx).append("\">")
              .append("<v>").append(value).append("</v></c>");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            String num = Double.isFinite(d) ? String.format(Locale.US, "%.6f", d) : "0";
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(styleIdx).append("\">")
              .append("<v>").append(num).append("</v></c>");
        } else {
            sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\" s=\"").append(styleIdx).append("\">")
              .append("<is><t xml:space=\"preserve\">").append(escapeXml(String.valueOf(value))).append("</t></is></c>");
        }
    }

    /** 0 -> A, 1 -> B, ... 26 -> AA, matching spreadsheet column naming. */
    private static String colLetter(int idx) {
        StringBuilder sb = new StringBuilder();
        int n = idx;
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    private static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                        // strip invalid XML control chars
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
