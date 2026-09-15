package it.govpay.fdr.batch.utils.jackson3;

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

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdScalarDeserializer;

import it.govpay.fdr.batch.Costanti;

/**
 * Deserializer Jackson 3 per OffsetDateTime con gestione flessibile e sicura.
 * Gestisce millisecondi a lunghezza variabile (1-9 cifre) dalle risposte pagoPA,
 * date senza secondi (es. 2025-12-09T00:00+01:00) e fallback su timezone CET
 * quando manca l'informazione di fuso orario.
 * <p>
 * Gemello Jackson 3 di {@link it.govpay.fdr.batch.utils.OffsetDateTimeDeserializer} (Jackson 2),
 * mantenuto separato perche' il client OpenAPI generato usa ancora Jackson 2.
 */
public class OffsetDateTimeDeserializer extends StdScalarDeserializer<OffsetDateTime> {

	private final transient DateTimeFormatter formatter;

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
	 * Costruttore di default con formato timestamp flessibile a millisecondi variabili.
	 */
	public OffsetDateTimeDeserializer() {
		this(Costanti.PATTERN_TIMESTAMP_3_YYYY_MM_DD_T_HH_MM_SS_SSSXXX);
	}

	/**
	 * Costruttore con pattern di formato personalizzato.
	 *
	 * @param format il pattern di formato data da usare per la deserializzazione
	 */
	public OffsetDateTimeDeserializer(String format) {
		super(OffsetDateTime.class);
		this.formatter = DateTimeFormatter.ofPattern(format, Locale.getDefault());
	}

	@Override
	public OffsetDateTime deserialize(JsonParser jsonParser, DeserializationContext ctxt) {
		JsonToken token = jsonParser.currentToken();

		if (token == JsonToken.VALUE_STRING) {
			try {
				return parseOffsetDateTime(jsonParser.getString(), this.formatter);
			} catch (DateTimeParseException e) {
				return ctxt.reportInputMismatch(OffsetDateTime.class,
						"Failed to parse OffsetDateTime: %s", e.getMessage());
			}
		}

		if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
			try {
				return parseEpochSeconds(jsonParser.getDecimalValue());
			} catch (ArithmeticException | DateTimeException e) {
				return ctxt.reportInputMismatch(OffsetDateTime.class,
						"Failed to parse OffsetDateTime from epoch: %s", e.getMessage());
			}
		}

		return null;
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
	 * Effettua il parsing di un OffsetDateTime con strategie di fallback multiple:
	 * <ol>
	 * <li>formatter fornito</li>
	 * <li>formatter OffsetDateTime flessibile (secondi/millis opzionali con timezone)</li>
	 * <li>LocalDateTime con formatter flessibile e offset CET</li>
	 * <li>LocalDateTime con formatter originale e offset CET</li>
	 * </ol>
	 *
	 * @param value la stringa data da parsare
	 * @param formatter il formatter primario da usare
	 * @return l'OffsetDateTime parsato oppure null se il valore e' null/vuoto
	 */
	public OffsetDateTime parseOffsetDateTime(String value, DateTimeFormatter formatter) {
		if (value != null && !value.trim().isEmpty()) {
			String dateString = value.trim();

			try {
				return OffsetDateTime.parse(dateString, formatter);
			} catch (DateTimeParseException e1) {
				// il formato principale non ha funzionato, provo con il formatter flessibile
			}

			try {
				return OffsetDateTime.parse(dateString, FLEXIBLE_OFFSET_FORMATTER);
			} catch (DateTimeParseException e2) {
				// provo come LocalDateTime con timezone CET
			}

			try {
				ZoneOffset offset = ZoneOffset.ofHoursMinutes(1, 0); // CET (Central European Time)
				LocalDateTime localDateTime = LocalDateTime.parse(dateString, FLEXIBLE_LOCAL_FORMATTER);
				return OffsetDateTime.of(localDateTime, offset);
			} catch (DateTimeParseException e3) {
				// ultimo tentativo con il formatter originale come LocalDateTime
			}

			ZoneOffset offset = ZoneOffset.ofHoursMinutes(1, 0); // CET (Central European Time)
			LocalDateTime localDateTime = LocalDateTime.parse(dateString, formatter);
			return OffsetDateTime.of(localDateTime, offset);
		}

		return null;
	}
}
