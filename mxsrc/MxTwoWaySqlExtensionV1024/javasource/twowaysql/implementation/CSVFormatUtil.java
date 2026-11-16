package twowaysql.implementation;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;

import twowaysql.proxies.CsvFormat;

public class CSVFormatUtil {
	public static org.apache.commons.csv.CSVFormat setupCSVFormat(CsvFormat myCsvFormat) {
		final CSVFormat.Builder formatBuilder = CSVFormat.RFC4180.builder();
		if (myCsvFormat != null) {
			if (myCsvFormat.getDelimiter() != null)
				formatBuilder.setDelimiter(myCsvFormat.getDelimiter());

			if (myCsvFormat.getQuoteCharacter() != null)
				formatBuilder.setQuote(myCsvFormat.getQuoteCharacter().charAt(0));

			if (myCsvFormat.getEscapeCharacter() != null)
				formatBuilder.setEscape(myCsvFormat.getEscapeCharacter().charAt(0));

			if ("CR".equals(myCsvFormat.getRecordSeparator()))
				formatBuilder.setRecordSeparator('\r');
			if ("LF".equals(myCsvFormat.getRecordSeparator()))
				formatBuilder.setRecordSeparator('\n');
			if ("CRLF".equals(myCsvFormat.getRecordSeparator()))
				formatBuilder.setRecordSeparator("\r\n");

			if (myCsvFormat.getTrim() != null)
				formatBuilder.setTrim(myCsvFormat.getTrim());

			if ("ALL".equals(myCsvFormat.getQuoteMode()))
				formatBuilder.setQuoteMode(QuoteMode.ALL);
			if ("ALL_NON_NULL".equals(myCsvFormat.getQuoteMode()))
				formatBuilder.setQuoteMode(QuoteMode.ALL_NON_NULL);
			if ("ALL_NON_NULL".equals(myCsvFormat.getQuoteMode()))
				formatBuilder.setQuoteMode(QuoteMode.ALL_NON_NULL);
			if ("MINIMAL".equals(myCsvFormat.getQuoteMode()))
				formatBuilder.setQuoteMode(QuoteMode.MINIMAL);
			if ("NON_NUMERIC".equals(myCsvFormat.getQuoteMode()))
				formatBuilder.setQuoteMode(QuoteMode.NON_NUMERIC);
			if ("NONE".equals(myCsvFormat.getQuoteMode()))
				formatBuilder.setQuoteMode(QuoteMode.NONE);
		}
		final CSVFormat csvFormat = formatBuilder.build();
		return csvFormat;
	}
}
