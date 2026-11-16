package twowaysql.implementation;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.mendix.core.Core;

import system.proxies.FileDocument;

/**
 * ResultSet を xlsx としてストリーミング出力する。外部ライブラリへの依存はない。
 *
 * <ul>
 *   <li>全列を文字列セル（インライン文字列）として出力する。型変換は SQL 側で行う前提。</li>
 *   <li>1 行目に列ラベル（ResultSetMetaData#getColumnLabel）を出力する。</li>
 *   <li>NULL と空文字は空セルになる。</li>
 *   <li>1 シートの上限（1,048,576 行）を超えるとシートを分割し、各シートにヘッダーを出力する。</li>
 *   <li>32,767 文字を超えるセル値は切り詰める。</li>
 *   <li>XML 1.0 で使えない制御文字は Excel のエスケープ形式（_xHHHH_）に変換する。</li>
 *   <li>シート名は指定がなければ Sheet1, Sheet2, ...。指定があればその名前を使い、
 *       分割時は 名前_1, 名前_2, ... とする。</li>
 * </ul>
 *
 * メモリ使用量は行数に依存しない。ResultSet のクローズは呼び出し側の責任。
 */
public final class ExcelExporter {

	private static final int MAX_ROWS_PER_SHEET = 1_048_576; // ヘッダー行を含む
	private static final int MAX_COLUMNS = 16_384;
	private static final int MAX_CELL_CHARS = 32_767;
	private static final int MAX_SHEET_NAME_CHARS = 31;
	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private static final String XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
	private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
	private static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
	private static final String NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships";

	private ExcelExporter() {
	}

	/**
	 * シート名を省略して出力する（Sheet1, Sheet2, ...）。
	 *
	 * @return 出力したデータ行数（ヘッダー行を含まない）
	 */
	public static long exportToExcel(FileDocument fileDocument, ResultSet resultSet, Long dsoptions, DateTimeFormatter dateFormat, ZoneId dbZone, ZoneId userZone)
			throws IOException, SQLException {
		return exportToExcel(fileDocument, resultSet, null, dsoptions, dateFormat, dbZone, userZone);
	}

	/**
	 * ResultSet の内容を xlsx として FileDocument に保存する。
	 * ファイル名（Name 属性）は呼び出し側で設定すること。
	 *
	 * @param sheetName シート名。null または空文字なら Sheet1, Sheet2, ...。
	 *                  行数超過でシートを分割した場合は 名前_1, 名前_2, ... とする。
	 *                  Excel で使えない文字（: \ / ? * [ ]）は "_" に置き換え、
	 *                  連番を含めて 31 文字に収まるよう切り詰める。
	 * @return 出力したデータ行数（ヘッダー行を含まない）
	 */
	public static long exportToExcel(FileDocument fileDocument, ResultSet resultSet, String sheetName, Long dsoptions, DateTimeFormatter dateFormat, ZoneId dbZone, ZoneId userZone)
			throws IOException, SQLException {
		Path tmp = Files.createTempFile(
				Core.getConfiguration().getTempPath().toPath(), "twowaysql-", ".xlsx");
		try {
			long rowCount;
			try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp))) {
				rowCount = writeXlsx(os, resultSet, sheetName, dsoptions, dateFormat, dbZone, userZone);
			}
			try (InputStream in = new BufferedInputStream(Files.newInputStream(tmp))) {
				Core.storeFileDocumentContent(
						fileDocument.getContext(), fileDocument.getMendixObject(), in);
			}
			return rowCount;
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * ResultSet の内容を xlsx 形式で OutputStream に書き出す。終了時に out をクローズする。
	 *
	 * @return 出力したデータ行数（ヘッダー行を含まない）
	 */
	static long writeXlsx(OutputStream out, ResultSet rs, String sheetName, Long dsoptions, DateTimeFormatter dateFormat, ZoneId dbZone, ZoneId userZone) throws IOException, SQLException {
		ResultSetMetaData md = rs.getMetaData();
		int cols = md.getColumnCount();
		if (cols > MAX_COLUMNS) {
			throw new IllegalArgumentException(
					"Column count " + cols + " exceeds Excel limit of " + MAX_COLUMNS);
		}
		String[] header = new String[cols];
		String[] colRefs = new String[cols];
		for (int c = 0; c < cols; c++) {
			header[c] = md.getColumnLabel(c + 1);
			colRefs[c] = columnName(c);
		}
		String[] values = new String[cols];
		long rowCount = 0;

		ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
		zip.setLevel(Deflater.BEST_SPEED); // 速度優先。サイズ優先なら DEFAULT_COMPRESSION
		try (Writer w = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 64 * 1024)) {

			int sheetCount = 0;
			boolean hasNext = rs.next();
			do { // 0 件でも 1 シート（ヘッダーのみ）は出力する
				sheetCount++;
				zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + sheetCount + ".xml"));
				w.write(XML_DECL);
				w.write("<worksheet xmlns=\"" + NS_MAIN + "\"><sheetData>");

				int rowNum = 1;
				writeRow(w, rowNum, header, colRefs);
				while (hasNext && rowNum < MAX_ROWS_PER_SHEET) {
					rowNum++;
					for (int c = 0; c < cols; c++) {
						//Object value = rs.getObject(c + 1);
						Object value = TwoWaySqlExecutor.readColumnValue(rs, c + 1, md);
						Object exportValue = TwoWaySqlExecutor.convertToExportValue(value, dateFormat, dbZone, userZone);
						values[c] = exportValue == null ? null : exportValue.toString();
					}
					writeRow(w, rowNum, values, colRefs);
					rowCount++;
					hasNext = rs.next();
				}

				w.write("</sheetData></worksheet>");
				w.flush();
				zip.closeEntry();
			} while (hasNext);

			writeEntry(zip, w, "[Content_Types].xml", contentTypes(sheetCount));
			writeEntry(zip, w, "_rels/.rels", rootRels());
			writeEntry(zip, w, "xl/workbook.xml", workbook(sheetNames(sheetName, sheetCount)));
			writeEntry(zip, w, "xl/_rels/workbook.xml.rels", workbookRels(sheetCount));
			writeEntry(zip, w, "xl/styles.xml", styles());
		}
		return rowCount;
	}

	private static void writeRow(Writer w, int rowNum, String[] values, String[] colRefs)
			throws IOException {
		String r = Integer.toString(rowNum);
		w.write("<row r=\"");
		w.write(r);
		w.write("\">");
		for (int c = 0; c < values.length; c++) {
			String v = values[c];
			if (v == null || v.isEmpty()) {
				continue;
			}
			w.write("<c r=\"");
			w.write(colRefs[c]);
			w.write(r);
			w.write("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">");
			writeEscaped(w, truncate(v));
			w.write("</t></is></c>");
		}
		w.write("</row>");
	}

	private static String truncate(String s) {
		if (s.length() <= MAX_CELL_CHARS) {
			return s;
		}
		int end = MAX_CELL_CHARS;
		if (Character.isHighSurrogate(s.charAt(end - 1))) {
			end--; // サロゲートペアを分断しない
		}
		return s.substring(0, end);
	}

	private static void writeEscaped(Writer w, String s) throws IOException {
		if (!needsEscape(s)) {
			w.write(s); // 大半の値はこの高速パスを通る
			return;
		}
		int len = s.length();
		for (int i = 0; i < len; i++) {
			char ch = s.charAt(i);
			switch (ch) {
			case '&':
				w.write("&amp;");
				break;
			case '<':
				w.write("&lt;");
				break;
			case '>':
				w.write("&gt;");
				break;
			case '\t':
			case '\n':
			case '\r':
				w.write(ch);
				break;
			case '_':
				// 元データ中の "_xHHHH_" が Excel にデコードされないよう "_" 自体をエスケープ
				w.write(isExcelEscapeAt(s, i) ? "_x005F_" : "_");
				break;
			default:
				if (ch < 0x20) {
					w.write("_x00");
					w.write(HEX[ch >> 4]);
					w.write(HEX[ch & 0xF]);
					w.write('_');
				} else if (Character.isHighSurrogate(ch)) {
					if (i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
						w.write(ch);
						w.write(s.charAt(++i));
					} // 対になっていないサロゲートは捨てる
				} else if (Character.isLowSurrogate(ch) || ch == 0xFFFE || ch == 0xFFFF) {
					// XML で使えない文字は捨てる
				} else {
					w.write(ch);
				}
			}
		}
	}

	private static boolean needsEscape(String s) {
		for (int i = 0, len = s.length(); i < len; i++) {
			char ch = s.charAt(i);
			if (ch < 0x20 || ch == '&' || ch == '<' || ch == '>' || ch == '_'
					|| Character.isSurrogate(ch) || ch == 0xFFFE || ch == 0xFFFF) {
				return true;
			}
		}
		return false;
	}

	private static boolean isExcelEscapeAt(String s, int i) {
		if (i + 6 >= s.length() || s.charAt(i + 1) != 'x' || s.charAt(i + 6) != '_') {
			return false;
		}
		for (int k = i + 2; k < i + 6; k++) {
			if (Character.digit(s.charAt(k), 16) < 0) {
				return false;
			}
		}
		return true;
	}

	/** 0 始まりの列番号を A, B, ..., Z, AA, ... に変換する。 */
	private static String columnName(int index) {
		StringBuilder sb = new StringBuilder();
		int n = index + 1;
		while (n > 0) {
			int rem = (n - 1) % 26;
			sb.insert(0, (char) ('A' + rem));
			n = (n - 1) / 26;
		}
		return sb.toString();
	}

	private static void writeEntry(ZipOutputStream zip, Writer w, String name, String content)
			throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		w.write(content);
		w.flush();
		zip.closeEntry();
	}

	private static String contentTypes(int sheetCount) {
		StringBuilder sb = new StringBuilder(XML_DECL)
				.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
				.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
				.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
				.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
				.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
		for (int i = 1; i <= sheetCount; i++) {
			sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
					.append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
		}
		return sb.append("</Types>").toString();
	}

	private static String rootRels() {
		return XML_DECL
				+ "<Relationships xmlns=\"" + NS_PKG_REL + "\">"
				+ "<Relationship Id=\"rId1\" Type=\"" + NS_REL + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
				+ "</Relationships>";
	}

	/** シート名の一覧を決める。 */
	public static String[] sheetNames(String requested, int sheetCount) {
		String[] names = new String[sheetCount];
		if (requested == null || requested.trim().isEmpty()) {
			for (int i = 0; i < sheetCount; i++) {
				names[i] = "Sheet" + (i + 1);
			}
			return names;
		}
		String base = sanitizeSheetName(requested.trim());
		if (sheetCount == 1) {
			names[0] = fitLength(base, MAX_SHEET_NAME_CHARS);
			return names;
		}
		for (int i = 0; i < sheetCount; i++) {
			String suffix = "_" + (i + 1);
			names[i] = fitLength(base, MAX_SHEET_NAME_CHARS - suffix.length()) + suffix;
		}
		return names;
	}

	private static String sanitizeSheetName(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (ch < 0x20 || ":\\/?*[]".indexOf(ch) >= 0) {
				sb.append('_');
			} else {
				sb.append(ch);
			}
		}
		// 先頭・末尾のアポストロフィは Excel で使えない
		if (sb.charAt(0) == '\'') {
			sb.setCharAt(0, '_');
		}
		if (sb.charAt(sb.length() - 1) == '\'') {
			sb.setCharAt(sb.length() - 1, '_');
		}
		// "History" は Excel の予約名
		if (sb.toString().equalsIgnoreCase("History")) {
			sb.append('_');
		}
		return sb.toString();
	}

	private static String fitLength(String s, int max) {
		if (s.length() <= max) {
			return s;
		}
		int end = max;
		if (Character.isHighSurrogate(s.charAt(end - 1))) {
			end--;
		}
		return s.substring(0, end);
	}

	private static String escapeAttr(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String workbook(String[] sheetNames) {
		StringBuilder sb = new StringBuilder(XML_DECL)
				.append("<workbook xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_REL)
				.append("\"><sheets>");
		for (int i = 1; i <= sheetNames.length; i++) {
			sb.append("<sheet name=\"").append(escapeAttr(sheetNames[i - 1])).append("\" sheetId=\"").append(i)
					.append("\" r:id=\"rId").append(i).append("\"/>");
		}
		return sb.append("</sheets></workbook>").toString();
	}

	private static String workbookRels(int sheetCount) {
		StringBuilder sb = new StringBuilder(XML_DECL)
				.append("<Relationships xmlns=\"").append(NS_PKG_REL).append("\">");
		for (int i = 1; i <= sheetCount; i++) {
			sb.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(NS_REL)
					.append("/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
		}
		sb.append("<Relationship Id=\"rId").append(sheetCount + 1).append("\" Type=\"").append(NS_REL)
				.append("/styles\" Target=\"styles.xml\"/>");
		return sb.append("</Relationships>").toString();
	}

	private static String styles() {
		return XML_DECL
				+ "<styleSheet xmlns=\"" + NS_MAIN + "\">"
				+ "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
				+ "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>"
				+ "<fill><patternFill patternType=\"gray125\"/></fill></fills>"
				+ "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
				+ "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
				+ "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>"
				+ "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
				+ "</styleSheet>";
	}
}
