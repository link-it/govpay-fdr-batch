package it.govpay.fdr.batch.utils;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;

/**
 * Custom deserializer for LocalDate that can handle both:
 * - Standard date format: "2025-03-12"
 * - Full datetime format: "2025-03-12T00:00:00.000000+02:00"
 *
 * This is needed because pagoPA API sometimes sends datetime strings for date fields.
 */
public class LocalDateFlexibleDeserializer extends StdScalarDeserializer<LocalDate> {

    private static final long serialVersionUID = 1L;

    public LocalDateFlexibleDeserializer() {
        super(LocalDate.class);
    }

    @Override
    public LocalDate deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
            throws IOException {
        try {
            JsonToken currentToken = jsonParser.getCurrentToken();
            if (currentToken == JsonToken.VALUE_STRING) {
                return parseLocalDate(jsonParser.getText());
            } else {
                return null;
            }
        } catch (IOException | DateTimeParseException e) {
            throw new IOException("Failed to parse LocalDate: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a LocalDate from string with multiple strategies:
     * 1. Try parsing as standard LocalDate (yyyy-MM-dd)
     * 2. Try parsing as OffsetDateTime and extract date part
     * 3. Try parsing as LocalDateTime and extract date part
     *
     * @param value the date string to parse
     * @return parsed LocalDate or null if value is null/empty
     */
    public LocalDate parseLocalDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        String dateString = value.trim();

        // First attempt: parse as standard LocalDate (yyyy-MM-dd)
        try {
            return LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            // Second attempt: parse as OffsetDateTime and extract date
            try {
                OffsetDateTime offsetDateTime = OffsetDateTime.parse(dateString,
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                return offsetDateTime.toLocalDate();
            } catch (DateTimeParseException e2) {
                // Third attempt: parse as LocalDateTime (without timezone) and extract date
                try {
                    return LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (DateTimeParseException e3) {
                    // If all attempts fail, rethrow original exception
                    throw e;
                }
            }
        }
    }
}
