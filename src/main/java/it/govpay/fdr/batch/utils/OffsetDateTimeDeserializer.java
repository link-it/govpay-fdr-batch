package it.govpay.fdr.batch.utils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;

import it.govpay.fdr.batch.Costanti;

/**
 * Custom deserializer for OffsetDateTime with enhanced security and flexibility.
 * Handles variable-length milliseconds (1-9 digits) from pagoPA API responses.
 * Falls back to CET timezone if parsing fails without timezone information.
 */
public class OffsetDateTimeDeserializer extends StdScalarDeserializer<OffsetDateTime> {

	private static final long serialVersionUID = 1L;

	private transient DateTimeFormatter formatter;

	/**
	 * Default constructor using flexible timestamp format with variable milliseconds.
	 */
	public OffsetDateTimeDeserializer() {
		this(Costanti.PATTERN_TIMESTAMP_3_YYYY_MM_DD_T_HH_MM_SS_SSSXXX);
	}

	/**
	 * Constructor with custom date format pattern.
	 *
	 * @param format the date format pattern to use for deserialization
	 */
	public OffsetDateTimeDeserializer(String format) {
		super(OffsetDateTime.class);
		this.formatter = DateTimeFormatter.ofPattern(format, Locale.getDefault());
	}

	@Override
	public OffsetDateTime deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
		try {
			JsonToken currentToken = jsonParser.getCurrentToken();
			if (currentToken == JsonToken.VALUE_STRING) {
				return parseOffsetDateTime(jsonParser.getText(), this.formatter);
			} else {
				return null;
			}
		} catch (IOException | DateTimeParseException e) {
			throw new IOException("Failed to parse OffsetDateTime: " + e.getMessage(), e);
		}
	}

	/**
	 * Parses an OffsetDateTime from string with fallback to CET timezone.
	 * First tries to parse with timezone, then falls back to LocalDateTime with CET offset.
	 *
	 * @param value the date string to parse
	 * @param formatter the date formatter to use
	 * @return parsed OffsetDateTime or null if value is null/empty
	 */
	public OffsetDateTime parseOffsetDateTime(String value, DateTimeFormatter formatter) {
		if (value != null && !value.trim().isEmpty()) {
			String dateString = value.trim();
			try {
				// First attempt: parse with timezone
				return OffsetDateTime.parse(dateString, formatter);
			} catch (DateTimeParseException e) {
				// Second attempt: parse as LocalDateTime and add CET offset
				try {
					ZoneOffset offset = ZoneOffset.ofHoursMinutes(1, 0); // CET (Central European Time)
					LocalDateTime localDateTime = LocalDateTime.parse(dateString, formatter);
					if (localDateTime != null) {
						return OffsetDateTime.of(localDateTime, offset);
					}
				} catch (DateTimeParseException ex) {
					// If both attempts fail, rethrow original exception
					throw e;
				}
			}
		}

		return null;
	}
}
