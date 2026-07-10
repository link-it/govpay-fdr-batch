package it.govpay.fdr.batch.utils.jackson3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Test per il LocalDateFlexibleDeserializer Jackson 3.
 */
@DisplayName("LocalDateFlexibleDeserializer (Jackson 3) Tests")
class LocalDateFlexibleDeserializerTest {

    private LocalDateFlexibleDeserializer deserializer;
    private JsonMapper mapper;

    @BeforeEach
    void setup() {
        deserializer = new LocalDateFlexibleDeserializer();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(LocalDate.class, deserializer);
        mapper = JsonMapper.builder().addModule(module).build();
    }

    private LocalDate read(String json) {
        return mapper.readValue(json, LocalDate.class);
    }

    @Test
    @DisplayName("Parse standard LocalDate format (yyyy-MM-dd)")
    void testParseStandardLocalDate() {
        assertEquals(LocalDate.of(2025, 3, 12), deserializer.parseLocalDate("2025-03-12"));
    }

    @Test
    @DisplayName("Parse OffsetDateTime format with timezone")
    void testParseOffsetDateTime() {
        assertEquals(LocalDate.of(2025, 3, 12), deserializer.parseLocalDate("2025-03-12T00:00:00.000000+02:00"));
    }

    @Test
    @DisplayName("Parse OffsetDateTime with Z timezone")
    void testParseOffsetDateTimeWithZ() {
        assertEquals(LocalDate.of(2025, 3, 12), deserializer.parseLocalDate("2025-03-12T10:30:00Z"));
    }

    @Test
    @DisplayName("Parse LocalDateTime format without timezone")
    void testParseLocalDateTime() {
        assertEquals(LocalDate.of(2025, 3, 12), deserializer.parseLocalDate("2025-03-12T14:30:00"));
    }

    @Test
    @DisplayName("Return null for null input")
    void testParseNullInput() {
        assertNull(deserializer.parseLocalDate(null));
    }

    @Test
    @DisplayName("Return null for empty string")
    void testParseEmptyString() {
        assertNull(deserializer.parseLocalDate(""));
    }

    @Test
    @DisplayName("Return null for blank string")
    void testParseBlankString() {
        assertNull(deserializer.parseLocalDate("   "));
    }

    @Test
    @DisplayName("Parse string with leading/trailing whitespace")
    void testParseWithWhitespace() {
        assertEquals(LocalDate.of(2025, 3, 12), deserializer.parseLocalDate("  2025-03-12  "));
    }

    @Test
    @DisplayName("Throw exception for invalid date format")
    void testParseInvalidFormat() {
        assertThrows(DateTimeParseException.class, () -> deserializer.parseLocalDate("invalid-date"));
    }

    @Test
    @DisplayName("Throw exception for partial date")
    void testParsePartialDate() {
        assertThrows(DateTimeParseException.class, () -> deserializer.parseLocalDate("2025-03"));
    }

    @Test
    @DisplayName("Deserialize with mapper - valid string token")
    void testDeserializeWithValidStringToken() {
        LocalDate result = read("\"2025-03-12\"");
        assertNotNull(result);
        assertEquals(LocalDate.of(2025, 3, 12), result);
    }

    @Test
    @DisplayName("Deserialize with mapper - OffsetDateTime string")
    void testDeserializeWithOffsetDateTime() {
        LocalDate result = read("\"2025-03-12T10:30:00+01:00\"");
        assertNotNull(result);
        assertEquals(LocalDate.of(2025, 3, 12), result);
    }

    @Test
    @DisplayName("Deserialize with mapper - null token")
    void testDeserializeWithNullToken() {
        assertNull(read("null"));
    }

    @Test
    @DisplayName("Deserialize with mapper - number token returns null")
    void testDeserializeWithNumberToken() {
        assertNull(read("123"));
    }

    @Test
    @DisplayName("Deserialize throws JacksonException for invalid date string")
    void testDeserializeThrowsOnInvalid() {
        JacksonException exception = assertThrows(JacksonException.class, () -> read("\"invalid-date\""));
        assertTrue(exception.getMessage().contains("Failed to parse LocalDate"));
    }

    @Test
    @DisplayName("Parse various ISO date formats")
    void testParseVariousISOFormats() {
        assertEquals(LocalDate.of(2025, 1, 15), deserializer.parseLocalDate("2025-01-15"));
        assertEquals(LocalDate.of(2025, 1, 15), deserializer.parseLocalDate("2025-01-15T14:30:00+01:00"));
        assertEquals(LocalDate.of(2025, 1, 15), deserializer.parseLocalDate("2025-01-15T14:30:00Z"));
        assertEquals(LocalDate.of(2025, 1, 15), deserializer.parseLocalDate("2025-01-15T14:30:00.123456+01:00"));
    }

    @Test
    @DisplayName("Parse edge case dates")
    void testParseEdgeCases() {
        assertEquals(LocalDate.of(2024, 2, 29), deserializer.parseLocalDate("2024-02-29"));
        assertEquals(LocalDate.of(2025, 12, 31), deserializer.parseLocalDate("2025-12-31"));
        assertEquals(LocalDate.of(2025, 1, 1), deserializer.parseLocalDate("2025-01-01"));
    }
}
