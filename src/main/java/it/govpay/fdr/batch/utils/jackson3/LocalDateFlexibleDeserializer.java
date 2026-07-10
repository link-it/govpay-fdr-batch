package it.govpay.fdr.batch.utils.jackson3;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdScalarDeserializer;

/**
 * Deserializer Jackson 3 per LocalDate che gestisce sia:
 * <ul>
 *   <li>formato data standard: "2025-03-12"</li>
 *   <li>formato datetime completo: "2025-03-12T00:00:00.000000+02:00"</li>
 * </ul>
 * Necessario perche' l'API pagoPA a volte invia stringhe datetime per campi data.
 * <p>
 * Gemello Jackson 3 di {@link it.govpay.fdr.batch.utils.LocalDateFlexibleDeserializer} (Jackson 2).
 */
public class LocalDateFlexibleDeserializer extends StdScalarDeserializer<LocalDate> {

	public LocalDateFlexibleDeserializer() {
		super(LocalDate.class);
	}

	@Override
	public LocalDate deserialize(JsonParser jsonParser, DeserializationContext ctxt) {
		if (jsonParser.currentToken() == JsonToken.VALUE_STRING) {
			try {
				return parseLocalDate(jsonParser.getString());
			} catch (DateTimeParseException e) {
				return ctxt.reportInputMismatch(LocalDate.class,
						"Failed to parse LocalDate: %s", e.getMessage());
			}
		}
		return null;
	}

	/**
	 * Effettua il parsing di un LocalDate con strategie multiple:
	 * <ol>
	 *   <li>LocalDate standard (yyyy-MM-dd)</li>
	 *   <li>OffsetDateTime, estraendo la parte data</li>
	 *   <li>LocalDateTime (senza timezone), estraendo la parte data</li>
	 * </ol>
	 *
	 * @param value la stringa data da parsare
	 * @return il LocalDate parsato oppure null se il valore e' null/vuoto
	 */
	public LocalDate parseLocalDate(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}

		String dateString = value.trim();

		try {
			return LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
		} catch (DateTimeParseException e) {
			try {
				OffsetDateTime offsetDateTime = OffsetDateTime.parse(dateString,
						DateTimeFormatter.ISO_OFFSET_DATE_TIME);
				return offsetDateTime.toLocalDate();
			} catch (DateTimeParseException e2) {
				try {
					return LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
				} catch (DateTimeParseException e3) {
					throw e;
				}
			}
		}
	}
}
