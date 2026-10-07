package it.govpay.fdr.batch.utils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;

import it.govpay.fdr.batch.Costanti;

/**
 * Custom deserializer for OffsetDateTime with enhanced security and flexibility.
 * Handles variable-length milliseconds (1-9 digits) from pagoPA API responses.
 * Also handles dates without seconds (e.g., 2025-12-09T00:00+01:00).
 * Falls back to CET timezone if parsing fails without timezone information.
 */
public class OffsetDateTimeDeserializer extends StdScalarDeserializer<OffsetDateTime> {

	private static final long serialVersionUID = 1L;

	private transient DateTimeFormatter formatter;

	/**
	 * Flexible formatter that handles:
	 * - Optional seconds
	 * - Optional milliseconds (1-9 digits)
	 * - Timezone offset (XXX format like +01:00 or Z)
	 */
	private static final DateTimeFormatter FLEXIBLE_OFFSET_FORMATTER = new DateTimeFormatterBuilder()
			.appendPattern("yyyy-MM-dd'T'HH:mm")
			.optionalStart()
			.appendPattern(":ss")
			.optionalEnd()
			.optionalStart()
			.appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
			.optionalEnd()
			.appendOffset("+HH:MM", "Z")
			.toFormatter(Locale.getDefault());

	/**
	 * Flexible formatter for LocalDateTime without timezone:
	 * - Optional seconds
	 * - Optional milliseconds (1-9 digits)
	 */
	private static final DateTimeFormatter FLEXIBLE_LOCAL_FORMATTER = new DateTimeFormatterBuilder()
			.appendPattern("yyyy-MM-dd'T'HH:mm")
			.optionalStart()
			.appendPattern(":ss")
			.optionalEnd()
			.optionalStart()
			.appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
			.optionalEnd()
			.toFormatter(Locale.getDefault());

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
			} else if (currentToken == JsonToken.VALUE_NUMBER_INT || currentToken == JsonToken.VALUE_NUMBER_FLOAT) {
				return parseEpochSeconds(jsonParser.getDecimalValue());
			} else {
				return null;
			}
		} catch (IOException | DateTimeParseException e) {
			throw new IOException("Failed to parse OffsetDateTime: " + e.getMessage(), e);
		} catch (ArithmeticException | DateTimeException e) {
			throw new IOException("Failed to parse OffsetDateTime from epoch: " + e.getMessage(), e);
		}
	}

	/**
	 * Converte un istante espresso come secondi dall'epoch, con eventuale parte
	 * frazionaria fino al nanosecondo (es. {@code 1786109246.000000000}).
	 * <p>
	 * Le API pagoPA serializzano gli {@code Instant} come stringa ISO, ma i tracciati
	 * depositati su file system possono arrivare da esportazioni che li scrivono come
	 * numero: senza questa gestione la data verrebbe silenziosamente persa.
	 * <p>
	 * Il valore e' un istante assoluto, quindi viene restituito con offset UTC; la
	 * conversione al fuso applicativo resta in carico ai processor.
	 */
	private static OffsetDateTime parseEpochSeconds(BigDecimal epochSeconds) {
		long secondi = epochSeconds.longValue();
		int nanos = epochSeconds.subtract(BigDecimal.valueOf(secondi))
				.movePointRight(9)
				.setScale(0, RoundingMode.DOWN)
				.intValueExact();
		return Instant.ofEpochSecond(secondi, nanos).atOffset(ZoneOffset.UTC);
	}

	/**
	 * Parses an OffsetDateTime from string with multiple fallback strategies.
	 * <ol>
	 * <li>Try the provided formatter</li>
	 * <li>Try the flexible OffsetDateTime formatter (handles optional seconds/millis with timezone)</li>
	 * <li>Try parsing as LocalDateTime with flexible formatter and add CET offset</li>
	 * <li>Try parsing as LocalDateTime with original formatter and add CET offset</li>
	 * </ol>
	 *
	 * @param value the date string to parse
	 * @param formatter the date formatter to use as primary attempt
	 * @return parsed OffsetDateTime or null if value is null/empty
	 */
	public OffsetDateTime parseOffsetDateTime(String value, DateTimeFormatter formatter) {
		if (value != null && !value.trim().isEmpty()) {
			String dateString = value.trim();

			// First attempt: parse with provided formatter
			try {
				return OffsetDateTime.parse(dateString, formatter);
			} catch (DateTimeParseException e1) {
				// Fallback: il formato principale non ha funzionato, provo con il formatter flessibile
			}

			// Second attempt: parse with flexible OffsetDateTime formatter
			// This handles formats like "2025-12-09T00:00+01:00" (no seconds)
			try {
				return OffsetDateTime.parse(dateString, FLEXIBLE_OFFSET_FORMATTER);
			} catch (DateTimeParseException e2) {
				// Fallback: provo come LocalDateTime con timezone CET
			}

			// Third attempt: parse as LocalDateTime with flexible formatter and add CET offset
			try {
				ZoneOffset offset = ZoneOffset.ofHoursMinutes(1, 0); // CET (Central European Time)
				LocalDateTime localDateTime = LocalDateTime.parse(dateString, FLEXIBLE_LOCAL_FORMATTER);
				return OffsetDateTime.of(localDateTime, offset);
			} catch (DateTimeParseException e3) {
				// Fallback: ultimo tentativo con il formatter originale come LocalDateTime
			}

			// Fourth attempt: try with original formatter as LocalDateTime
			try {
				ZoneOffset offset = ZoneOffset.ofHoursMinutes(1, 0); // CET (Central European Time)
				LocalDateTime localDateTime = LocalDateTime.parse(dateString, formatter);
				return OffsetDateTime.of(localDateTime, offset);
			} catch (DateTimeParseException e4) {
				// All attempts failed, throw descriptive exception
				throw new DateTimeParseException(
						"Unable to parse date '" + dateString + "' with any supported format",
						dateString,
						0);
			}
		}

		return null;
	}
}
