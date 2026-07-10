package it.govpay.fdr.batch.utils.jackson3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import it.govpay.fdr.batch.Costanti;

/**
 * Unit tests for the Jackson 3 OffsetDateTimeDeserializer.
 * Tests security features like variable milliseconds and CET fallback.
 */
class OffsetDateTimeDeserializerTest {

    private static OffsetDateTime read(OffsetDateTimeDeserializer deserializer, String json) {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(OffsetDateTime.class, deserializer);
        JsonMapper mapper = JsonMapper.builder().addModule(module).build();
        return mapper.readValue(json, OffsetDateTime.class);
    }

    @Test
    @DisplayName("Should deserialize with default format (with timezone)")
    void testDeserializeWithDefaultFormat() {
        OffsetDateTime result = read(new OffsetDateTimeDeserializer(), "\"2025-01-27T10:30:45.123+01:00\"");
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
    void testDeserializeVariableMilliseconds() {
        OffsetDateTimeDeserializer deserializer =
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI);
        assertThat(read(deserializer, "\"2025-01-27T10:30:45.1\"").getNano()).isEqualTo(100_000_000);
        assertThat(read(deserializer, "\"2025-01-27T10:30:45.123\"").getNano()).isEqualTo(123_000_000);
        assertThat(read(deserializer, "\"2025-01-27T10:30:45.123456\"").getNano()).isEqualTo(123_456_000);
        assertThat(read(deserializer, "\"2025-01-27T10:30:45.123456789\"").getNano()).isEqualTo(123_456_789);
    }

    @Test
    @DisplayName("Should apply CET fallback when timezone is missing")
    void testCETFallback() {
        OffsetDateTime result = read(
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI),
            "\"2025-01-27T10:30:45.123\"");
        assertThat(result).isNotNull();
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(1, 0));
        assertThat(result.getYear()).isEqualTo(2025);
        assertThat(result.getNano()).isEqualTo(123_000_000);
    }

    @Test
    @DisplayName("Should use provided timezone when present")
    void testUseProvidedTimezone() {
        OffsetDateTime result = read(
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX),
            "\"2025-01-27T10:30:45.123+05:00\"");
        assertThat(result).isNotNull();
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(5));
    }

    @Test
    @DisplayName("Should handle null JSON value")
    void testDeserializeNull() {
        assertThat(read(new OffsetDateTimeDeserializer(), "null")).isNull();
    }

    @Test
    @DisplayName("Should handle empty string")
    void testDeserializeEmptyString() {
        assertThat(read(new OffsetDateTimeDeserializer(), "\"\"")).isNull();
    }

    @Test
    @DisplayName("Should handle whitespace-only string")
    void testDeserializeWhitespaceString() {
        assertThat(read(new OffsetDateTimeDeserializer(), "\"   \"")).isNull();
    }

    @Test
    @DisplayName("Should handle UTC timezone (Z)")
    void testDeserializeUTC() {
        OffsetDateTime result = read(
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX),
            "\"2025-01-27T10:30:45.123Z\"");
        assertThat(result).isNotNull();
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("Should handle negative timezone offset")
    void testDeserializeNegativeOffset() {
        OffsetDateTime result = read(
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX),
            "\"2025-01-27T10:30:45.123-05:00\"");
        assertThat(result).isNotNull();
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(-5));
    }

    @Test
    @DisplayName("Should throw JacksonException for invalid date format")
    void testDeserializeInvalidFormat() {
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer();
        assertThatThrownBy(() -> read(deserializer, "\"invalid-date-format\""))
            .isInstanceOf(JacksonException.class)
            .hasMessageContaining("Failed to parse OffsetDateTime");
    }

    @Test
    @DisplayName("parseOffsetDateTime should handle dates without milliseconds")
    void testParseWithoutMilliseconds() {
        OffsetDateTimeDeserializer deserializer =
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI);
        OffsetDateTime result = deserializer.parseOffsetDateTime("2025-01-27T10:30:45", formatter);
        assertThat(result).isNotNull();
        assertThat(result.getNano()).isEqualTo(0);
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(1, 0));
    }

    @Test
    @DisplayName("parseOffsetDateTime should return null for null input")
    void testParseNullInput() {
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Costanti.PATTERN_TIMESTAMP_3_YYYY_MM_DD_T_HH_MM_SS_SSSXXX);
        assertThat(deserializer.parseOffsetDateTime(null, formatter)).isNull();
    }

    @Test
    @DisplayName("parseOffsetDateTime should return null for empty input")
    void testParseEmptyInput() {
        OffsetDateTimeDeserializer deserializer = new OffsetDateTimeDeserializer();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Costanti.PATTERN_TIMESTAMP_3_YYYY_MM_DD_T_HH_MM_SS_SSSXXX);
        assertThat(deserializer.parseOffsetDateTime("", formatter)).isNull();
    }

    @Test
    @DisplayName("Should handle dates at midnight")
    void testDeserializeMidnight() {
        OffsetDateTime result = read(
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI),
            "\"2025-01-27T00:00:00.000\"");
        assertThat(result).isNotNull();
        assertThat(result.getHour()).isEqualTo(0);
        assertThat(result.getMinute()).isEqualTo(0);
        assertThat(result.getSecond()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle date without seconds with timezone (pagoPA format)")
    void testDeserializeDateWithoutSeconds() {
        OffsetDateTime result = read(
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX),
            "\"2025-12-09T00:00+01:00\"");
        assertThat(result).isNotNull();
        assertThat(result.getYear()).isEqualTo(2025);
        assertThat(result.getMonthValue()).isEqualTo(12);
        assertThat(result.getDayOfMonth()).isEqualTo(9);
        assertThat(result.getHour()).isEqualTo(0);
        assertThat(result.getMinute()).isEqualTo(0);
        assertThat(result.getSecond()).isEqualTo(0);
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(1));
    }

    @Test
    @DisplayName("Should handle date without seconds with UTC timezone")
    void testDeserializeDateWithoutSecondsUTC() {
        OffsetDateTime result = read(
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX),
            "\"2025-12-09T15:30Z\"");
        assertThat(result).isNotNull();
        assertThat(result.getHour()).isEqualTo(15);
        assertThat(result.getMinute()).isEqualTo(30);
        assertThat(result.getSecond()).isEqualTo(0);
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("Should handle date without seconds with negative offset")
    void testDeserializeDateWithoutSecondsNegativeOffset() {
        OffsetDateTime result = read(
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX),
            "\"2025-12-09T10:45-05:00\"");
        assertThat(result).isNotNull();
        assertThat(result.getHour()).isEqualTo(10);
        assertThat(result.getMinute()).isEqualTo(45);
        assertThat(result.getSecond()).isEqualTo(0);
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(-5));
    }

    @Test
    @DisplayName("Should handle date without seconds without timezone (fallback to CET)")
    void testDeserializeDateWithoutSecondsNoTimezone() {
        OffsetDateTime result = read(
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI),
            "\"2025-12-09T14:30\"");
        assertThat(result).isNotNull();
        assertThat(result.getHour()).isEqualTo(14);
        assertThat(result.getMinute()).isEqualTo(30);
        assertThat(result.getSecond()).isEqualTo(0);
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(1));
    }

    @Test
    @DisplayName("Should handle dates at end of day")
    void testDeserializeEndOfDay() {
        OffsetDateTime result = read(
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI),
            "\"2025-01-27T23:59:59.999\"");
        assertThat(result).isNotNull();
        assertThat(result.getHour()).isEqualTo(23);
        assertThat(result.getMinute()).isEqualTo(59);
        assertThat(result.getSecond()).isEqualTo(59);
        assertThat(result.getNano()).isEqualTo(999_000_000);
    }
}
