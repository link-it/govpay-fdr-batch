package it.govpay.fdr.client;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.ParsePosition;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import tools.jackson.databind.util.StdDateFormat;

/**
 * Formato data RFC3339 usato dall'{@code ApiClient} generato per i valori {@code java.util.Date}.
 * <p>
 * Versione fornita a mano (non generata da OpenAPI Generator) basata su Jackson 3
 * ({@link tools.jackson.databind.util.StdDateFormat}), per non introdurre dipendenze da Jackson 2.
 * Vedi {@code .openapi-generator-ignore}.
 */
public class RFC3339DateFormat extends DateFormat {
	private static final long serialVersionUID = 1L;
	private static final TimeZone TIMEZONE_Z = TimeZone.getTimeZone("UTC");

	private final transient StdDateFormat fmt = new StdDateFormat()
			.withTimeZone(TIMEZONE_Z)
			.withColonInTimeZone(true);

	public RFC3339DateFormat() {
		this.calendar = new GregorianCalendar();
		this.numberFormat = new DecimalFormat();
	}

	@Override
	public Date parse(String source) {
		return parse(source, new ParsePosition(0));
	}

	@Override
	public Date parse(String source, ParsePosition pos) {
		return fmt.parse(source, pos);
	}

	@Override
	public StringBuffer format(Date date, StringBuffer toAppendTo, FieldPosition fieldPosition) {
		return fmt.format(date, toAppendTo, fieldPosition);
	}

	@Override
	public Object clone() {
		return super.clone();
	}
}
