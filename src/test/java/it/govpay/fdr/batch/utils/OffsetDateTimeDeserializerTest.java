package it.govpay.fdr.batch.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;

import it.govpay.fdr.batch.Costanti;

/**
 * Unit tests for OffsetDateTimeDeserializer custom deserializer.
 * Tests security features like variable milliseconds and CET fallback.
 */
class OffsetDateTimeDeserializerTest {

    @Test
    @DisplayName("Should deserialize with default format (with timezone)")
    void testDeserializeWithDefaultFormat() throws IOException {
        // Given
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer();
        String json = "\"2025-01-27T10:30:45.123+01:00\"";

        JsonParser jsonParser = new JsonFactory().createParser(json);
        jsonParser.nextToken(); // Move to VALUE_STRING
        DeserializationContext context = null; // Not needed for this test

        // When
        OffsetDateTime result = deserializer.deserialize(jsonParser, context);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getYear()).isEqualTo(2025);
        assertThat(result.getMonthValue()).isEqualTo(1);
        assertThat(result.getDayOfMonth()).isEqualTo(27);
        assertThat(result.getHour()).isEqualTo(10);
        assertThat(result.getMinute()).isEqualTo(30);
        assertThat(result.getSecond()).isEqualTo(45);
        assertThat(result.getNano()).isEqualTo(123_000_000);
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(1));
    }

    @Test
    @DisplayName("Should deserialize with variable milliseconds format")
    void testDeserializeVariableMilliseconds() throws IOException {
        // Given: Custom deserializer with variable milliseconds pattern
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer(
            Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI
        );

        // Test 1 digit
        String json1 = "\"2025-01-27T10:30:45.1\"";
        JsonParser parser1 = new JsonFactory().createParser(json1);
        parser1.nextToken();
        OffsetDateTime result1 = deserializer.deserialize(parser1, null);
        assertThat(result1.getNano()).isEqualTo(100_000_000);

        // Test 3 digits
        String json3 = "\"2025-01-27T10:30:45.123\"";
        JsonParser parser3 = new JsonFactory().createParser(json3);
        parser3.nextToken();
        OffsetDateTime result3 = deserializer.deserialize(parser3, null);
        assertThat(result3.getNano()).isEqualTo(123_000_000);

        // Test 6 digits
        String json6 = "\"2025-01-27T10:30:45.123456\"";
        JsonParser parser6 = new JsonFactory().createParser(json6);
        parser6.nextToken();
        OffsetDateTime result6 = deserializer.deserialize(parser6, null);
        assertThat(result6.getNano()).isEqualTo(123_456_000);

        // Test 9 digits
        String json9 = "\"2025-01-27T10:30:45.123456789\"";
        JsonParser parser9 = new JsonFactory().createParser(json9);
        parser9.nextToken();
        OffsetDateTime result9 = deserializer.deserialize(parser9, null);
        assertThat(result9.getNano()).isEqualTo(123_456_789);
    }

    @Test
    @DisplayName("Should apply CET fallback when timezone is missing")
    void testCETFallback() throws IOException {
        // Given: Deserializer with variable millis pattern (no timezone)
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer(
            Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI
        );
        String json = "\"2025-01-27T10:30:45.123\""; // No timezone

        JsonParser jsonParser = new JsonFactory().createParser(json);
        jsonParser.nextToken();

        // When
        OffsetDateTime result = deserializer.deserialize(jsonParser, null);

        // Then: Should apply CET offset +01:00
        assertThat(result).isNotNull();
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(1, 0));
        assertThat(result.getYear()).isEqualTo(2025);
        assertThat(result.getNano()).isEqualTo(123_000_000);
    }

    @Test
    @DisplayName("Should use provided timezone when present")
    void testUseProvidedTimezone() throws IOException {
        // Given: JSON with explicit timezone
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer(
            Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX
        );
        String json = "\"2025-01-27T10:30:45.123+05:00\""; // JST-like

        JsonParser jsonParser = new JsonFactory().createParser(json);
        jsonParser.nextToken();

        // When
        OffsetDateTime result = deserializer.deserialize(jsonParser, null);

        // Then: Should use the provided timezone, not CET
        assertThat(result).isNotNull();
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(5));
    }

    @Test
    @DisplayName("Should handle null JSON value")
    void testDeserializeNull() throws IOException {
        // Given
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer();
        String json = "null";

        JsonParser jsonParser = new JsonFactory().createParser(json);
        jsonParser.nextToken(); // Move to VALUE_NULL

        // When
        OffsetDateTime result = deserializer.deserialize(jsonParser, null);

        // Then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should handle empty string")
    void testDeserializeEmptyString() throws IOException {
        // Given
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer();
        String json = "\"\"";

        JsonParser jsonParser = new JsonFactory().createParser(json);
        jsonParser.nextToken();

        // When
        OffsetDateTime result = deserializer.deserialize(jsonParser, null);

        // Then: Empty string should return null
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should handle whitespace-only string")
    void testDeserializeWhitespaceString() throws IOException {
        // Given
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer();
        String json = "\"   \"";

        JsonParser jsonParser = new JsonFactory().createParser(json);
        jsonParser.nextToken();

        // When
        OffsetDateTime result = deserializer.deserialize(jsonParser, null);

        // Then: Whitespace-only should return null
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should handle UTC timezone (Z)")
    void testDeserializeUTC() throws IOException {
        // Given
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer(
            Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX
        );
        String json = "\"2025-01-27T10:30:45.123Z\"";

        JsonParser jsonParser = new JsonFactory().createParser(json);
        jsonParser.nextToken();

        // When
        OffsetDateTime result = deserializer.deserialize(jsonParser, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("Should handle negative timezone offset")
    void testDeserializeNegativeOffset() throws IOException {
        // Given
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer(
            Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX
        );
        String json = "\"2025-01-27T10:30:45.123-05:00\"";

        JsonParser jsonParser = new JsonFactory().createParser(json);
        jsonParser.nextToken();

        // When
        OffsetDateTime result = deserializer.deserialize(jsonParser, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(-5));
    }

    @Test
    @DisplayName("Should throw IOException for invalid date format")
    void testDeserializeInvalidFormat() throws IOException {
        // Given
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer();
        String json = "\"invalid-date-format\"";

        JsonParser jsonParser = new JsonFactory().createParser(json);
        jsonParser.nextToken();

        // When/Then
        assertThatThrownBy(() -> deserializer.deserialize(jsonParser, null))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Failed to parse OffsetDateTime");
    }

    @Test
    @DisplayName("parseOffsetDateTime should handle dates without milliseconds")
    void testParseWithoutMilliseconds() {
        // Given
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer(
            Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI
        );
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
            Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI
        );
        String dateString = "2025-01-27T10:30:45";

        // When
        OffsetDateTime result = deserializer.parseOffsetDateTime(dateString, formatter);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getNano()).isEqualTo(0);
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(1, 0)); // CET fallback
    }

    @Test
    @DisplayName("parseOffsetDateTime should return null for null input")
    void testParseNullInput() {
        // Given
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
            Costanti.PATTERN_TIMESTAMP_3_YYYY_MM_DD_T_HH_MM_SS_SSSXXX
        );

        // When
        OffsetDateTime result = deserializer.parseOffsetDateTime(null, formatter);

        // Then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("parseOffsetDateTime should return null for empty input")
    void testParseEmptyInput() {
        // Given
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
            Costanti.PATTERN_TIMESTAMP_3_YYYY_MM_DD_T_HH_MM_SS_SSSXXX
        );

        // When
        OffsetDateTime result = deserializer.parseOffsetDateTime("", formatter);

        // Then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should handle dates at midnight")
    void testDeserializeMidnight() throws IOException {
        // Given
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer(
            Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI
        );
        String json = "\"2025-01-27T00:00:00.000\"";

        JsonParser jsonParser = new JsonFactory().createParser(json);
        jsonParser.nextToken();

        // When
        OffsetDateTime result = deserializer.deserialize(jsonParser, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getHour()).isEqualTo(0);
        assertThat(result.getMinute()).isEqualTo(0);
        assertThat(result.getSecond()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle dates at end of day")
    void testDeserializeEndOfDay() throws IOException {
        // Given
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer(
            Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI
        );
        String json = "\"2025-01-27T23:59:59.999\"";

        JsonParser jsonParser = new JsonFactory().createParser(json);
        jsonParser.nextToken();

        // When
        OffsetDateTime result = deserializer.deserialize(jsonParser, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getHour()).isEqualTo(23);
        assertThat(result.getMinute()).isEqualTo(59);
        assertThat(result.getSecond()).isEqualTo(59);
        assertThat(result.getNano()).isEqualTo(999_000_000);
    }
}
