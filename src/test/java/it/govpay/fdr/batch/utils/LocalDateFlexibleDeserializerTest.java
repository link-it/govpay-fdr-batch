package it.govpay.fdr.batch.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;

/**
 * Test per LocalDateFlexibleDeserializer
 */
@DisplayName("LocalDateFlexibleDeserializer Tests")
class LocalDateFlexibleDeserializerTest {

    private LocalDateFlexibleDeserializer deserializer;

    @BeforeEach
    void setup() {
        deserializer = new LocalDateFlexibleDeserializer();
    }

    @Test
    @DisplayName("Parse standard LocalDate format (yyyy-MM-dd)")
    void testParseStandardLocalDate() {
        String dateString = "2025-03-12";
        LocalDate result = deserializer.parseLocalDate(dateString);

        assertNotNull(result);
        assertEquals(LocalDate.of(2025, 3, 12), result);
    }

    @Test
    @DisplayName("Parse OffsetDateTime format with timezone")
    void testParseOffsetDateTime() {
        String dateString = "2025-03-12T00:00:00.000000+02:00";
        LocalDate result = deserializer.parseLocalDate(dateString);

        assertNotNull(result);
        assertEquals(LocalDate.of(2025, 3, 12), result);
    }

    @Test
    @DisplayName("Parse OffsetDateTime with Z timezone")
    void testParseOffsetDateTimeWithZ() {
        String dateString = "2025-03-12T10:30:00Z";
        LocalDate result = deserializer.parseLocalDate(dateString);

        assertNotNull(result);
        assertEquals(LocalDate.of(2025, 3, 12), result);
    }

    @Test
    @DisplayName("Parse LocalDateTime format without timezone")
    void testParseLocalDateTime() {
        String dateString = "2025-03-12T14:30:00";
        LocalDate result = deserializer.parseLocalDate(dateString);

        assertNotNull(result);
        assertEquals(LocalDate.of(2025, 3, 12), result);
    }

    @Test
    @DisplayName("Return null for null input")
    void testParseNullInput() {
        LocalDate result = deserializer.parseLocalDate(null);
        assertNull(result);
    }

    @Test
    @DisplayName("Return null for empty string")
    void testParseEmptyString() {
        LocalDate result = deserializer.parseLocalDate("");
        assertNull(result);
    }

    @Test
    @DisplayName("Return null for blank string")
    void testParseBlankString() {
        LocalDate result = deserializer.parseLocalDate("   ");
        assertNull(result);
    }

    @Test
    @DisplayName("Parse string with leading/trailing whitespace")
    void testParseWithWhitespace() {
        String dateString = "  2025-03-12  ";
        LocalDate result = deserializer.parseLocalDate(dateString);

        assertNotNull(result);
        assertEquals(LocalDate.of(2025, 3, 12), result);
    }

    @Test
    @DisplayName("Throw exception for invalid date format")
    void testParseInvalidFormat() {
        String dateString = "invalid-date";
        assertThrows(DateTimeParseException.class, () -> {
            deserializer.parseLocalDate(dateString);
        });
    }

    @Test
    @DisplayName("Throw exception for partial date")
    void testParsePartialDate() {
        String dateString = "2025-03";
        assertThrows(DateTimeParseException.class, () -> {
            deserializer.parseLocalDate(dateString);
        });
    }

    @Test
    @DisplayName("Deserialize with JsonParser - valid string token")
    void testDeserializeWithValidStringToken() throws IOException {
        JsonParser jsonParser = mock(JsonParser.class);
        DeserializationContext context = mock(DeserializationContext.class);

        when(jsonParser.getCurrentToken()).thenReturn(JsonToken.VALUE_STRING);
        when(jsonParser.getText()).thenReturn("2025-03-12");

        LocalDate result = deserializer.deserialize(jsonParser, context);

        assertNotNull(result);
        assertEquals(LocalDate.of(2025, 3, 12), result);
    }

    @Test
    @DisplayName("Deserialize with JsonParser - OffsetDateTime string")
    void testDeserializeWithOffsetDateTime() throws IOException {
        JsonParser jsonParser = mock(JsonParser.class);
        DeserializationContext context = mock(DeserializationContext.class);

        when(jsonParser.getCurrentToken()).thenReturn(JsonToken.VALUE_STRING);
        when(jsonParser.getText()).thenReturn("2025-03-12T10:30:00+01:00");

        LocalDate result = deserializer.deserialize(jsonParser, context);

        assertNotNull(result);
        assertEquals(LocalDate.of(2025, 3, 12), result);
    }

    @Test
    @DisplayName("Deserialize with JsonParser - null token")
    void testDeserializeWithNullToken() throws IOException {
        JsonParser jsonParser = mock(JsonParser.class);
        DeserializationContext context = mock(DeserializationContext.class);

        when(jsonParser.getCurrentToken()).thenReturn(JsonToken.VALUE_NULL);

        LocalDate result = deserializer.deserialize(jsonParser, context);

        assertNull(result);
    }

    @Test
    @DisplayName("Deserialize with JsonParser - number token returns null")
    void testDeserializeWithNumberToken() throws IOException {
        JsonParser jsonParser = mock(JsonParser.class);
        DeserializationContext context = mock(DeserializationContext.class);

        when(jsonParser.getCurrentToken()).thenReturn(JsonToken.VALUE_NUMBER_INT);

        LocalDate result = deserializer.deserialize(jsonParser, context);

        assertNull(result);
    }

    @Test
    @DisplayName("Deserialize throws IOException for invalid date string")
    void testDeserializeThrowsIOException() throws IOException {
        JsonParser jsonParser = mock(JsonParser.class);
        DeserializationContext context = mock(DeserializationContext.class);

        when(jsonParser.getCurrentToken()).thenReturn(JsonToken.VALUE_STRING);
        when(jsonParser.getText()).thenReturn("invalid-date");

        IOException exception = assertThrows(IOException.class, () -> {
            deserializer.deserialize(jsonParser, context);
        });

        assertTrue(exception.getMessage().contains("Failed to parse LocalDate"));
    }

    @Test
    @DisplayName("Parse various ISO date formats")
    void testParseVariousISOFormats() {
        // Standard date
        assertEquals(LocalDate.of(2025, 1, 15),
            deserializer.parseLocalDate("2025-01-15"));

        // With time and timezone
        assertEquals(LocalDate.of(2025, 1, 15),
            deserializer.parseLocalDate("2025-01-15T14:30:00+01:00"));

        // With UTC timezone
        assertEquals(LocalDate.of(2025, 1, 15),
            deserializer.parseLocalDate("2025-01-15T14:30:00Z"));

        // With microseconds
        assertEquals(LocalDate.of(2025, 1, 15),
            deserializer.parseLocalDate("2025-01-15T14:30:00.123456+01:00"));
    }

    @Test
    @DisplayName("Parse edge case dates")
    void testParseEdgeCases() {
        // Leap year
        assertEquals(LocalDate.of(2024, 2, 29),
            deserializer.parseLocalDate("2024-02-29"));

        // End of year
        assertEquals(LocalDate.of(2025, 12, 31),
            deserializer.parseLocalDate("2025-12-31"));

        // Start of year
        assertEquals(LocalDate.of(2025, 1, 1),
            deserializer.parseLocalDate("2025-01-01"));
    }
}
