package it.govpay.fdr.batch.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;

import it.govpay.fdr.batch.Costanti;

/**
 * Unit tests for OffsetDateTimeSerializer custom serializer.
 */
class OffsetDateTimeSerializerTest {

    @Test
    @DisplayName("Should serialize OffsetDateTime with default format (SSSXXX)")
    void testSerializeWithDefaultFormat() throws Exception {
        // Given
        OffsetDateTimeSerializer serializer = new OffsetDateTimeSerializer();
        OffsetDateTime dateTime = OffsetDateTime.of(
            2025, 1, 27, 10, 30, 45, 123_000_000,
            ZoneOffset.ofHours(1)
        );

        StringWriter writer = new StringWriter();
        JsonGenerator jsonGenerator = new JsonFactory().createGenerator(writer);
        SerializerProvider serializerProvider = null; // Not needed for this test

        // When
        serializer.serialize(dateTime, jsonGenerator, serializerProvider);
        jsonGenerator.flush();

        // Then: Should use format yyyy-MM-dd'T'HH:mm:ss.SSSXXX
        String result = writer.toString();
        assertThat(result).isEqualTo("\"2025-01-27T10:30:45.123+01:00\"");
    }

    @Test
    @DisplayName("Should serialize OffsetDateTime with custom format (SSS)")
    void testSerializeWithCustomFormat() throws Exception {
        // Given: Serializer with custom format (no timezone)
        OffsetDateTimeSerializer serializer = new OffsetDateTimeSerializer(
            Costanti.PATTERN_DATA_JSON_YYYY_MM_DD_T_HH_MM_SS_SSS
        );
        OffsetDateTime dateTime = OffsetDateTime.of(
            2025, 1, 27, 10, 30, 45, 123_000_000,
            ZoneOffset.ofHours(1)
        );

        StringWriter writer = new StringWriter();
        JsonGenerator jsonGenerator = new JsonFactory().createGenerator(writer);
        SerializerProvider serializerProvider = null;

        // When
        serializer.serialize(dateTime, jsonGenerator, serializerProvider);
        jsonGenerator.flush();

        // Then: Should use format yyyy-MM-dd'T'HH:mm:ss.SSS (no timezone)
        String result = writer.toString();
        assertThat(result).isEqualTo("\"2025-01-27T10:30:45.123\"");
    }

    @Test
    @DisplayName("Should serialize null as null")
    void testSerializeNull() throws Exception {
        // Given
        OffsetDateTimeSerializer serializer = new OffsetDateTimeSerializer();
        OffsetDateTime dateTime = null;

        StringWriter writer = new StringWriter();
        JsonGenerator jsonGenerator = new JsonFactory().createGenerator(writer);
        SerializerProvider serializerProvider = null;

        // When
        serializer.serialize(dateTime, jsonGenerator, serializerProvider);
        jsonGenerator.flush();

        // Then
        String result = writer.toString();
        assertThat(result).isEqualTo("null");
    }

    @Test
    @DisplayName("Should serialize UTC timezone correctly")
    void testSerializeUTC() throws Exception {
        // Given
        OffsetDateTimeSerializer serializer = new OffsetDateTimeSerializer();
        OffsetDateTime dateTime = OffsetDateTime.of(
            2025, 1, 27, 10, 30, 45, 123_000_000,
            ZoneOffset.UTC
        );

        StringWriter writer = new StringWriter();
        JsonGenerator jsonGenerator = new JsonFactory().createGenerator(writer);
        SerializerProvider serializerProvider = null;

        // When
        serializer.serialize(dateTime, jsonGenerator, serializerProvider);
        jsonGenerator.flush();

        // Then: Should use Z for UTC
        String result = writer.toString();
        assertThat(result).isEqualTo("\"2025-01-27T10:30:45.123Z\"");
    }

    @Test
    @DisplayName("Should serialize negative timezone offset")
    void testSerializeNegativeOffset() throws Exception {
        // Given
        OffsetDateTimeSerializer serializer = new OffsetDateTimeSerializer();
        OffsetDateTime dateTime = OffsetDateTime.of(
            2025, 1, 27, 10, 30, 45, 123_000_000,
            ZoneOffset.ofHours(-5) // EST
        );

        StringWriter writer = new StringWriter();
        JsonGenerator jsonGenerator = new JsonFactory().createGenerator(writer);
        SerializerProvider serializerProvider = null;

        // When
        serializer.serialize(dateTime, jsonGenerator, serializerProvider);
        jsonGenerator.flush();

        // Then
        String result = writer.toString();
        assertThat(result).isEqualTo("\"2025-01-27T10:30:45.123-05:00\"");
    }

    @Test
    @DisplayName("Should maintain millisecond precision")
    void testMillisecondPrecision() throws Exception {
        // Given
        OffsetDateTimeSerializer serializer = new OffsetDateTimeSerializer();
        OffsetDateTime dateTime = OffsetDateTime.of(
            2025, 1, 27, 10, 30, 45, 999_000_000, // 999 milliseconds
            ZoneOffset.ofHours(1)
        );

        StringWriter writer = new StringWriter();
        JsonGenerator jsonGenerator = new JsonFactory().createGenerator(writer);
        SerializerProvider serializerProvider = null;

        // When
        serializer.serialize(dateTime, jsonGenerator, serializerProvider);
        jsonGenerator.flush();

        // Then
        String result = writer.toString();
        assertThat(result).contains(".999");
    }

    @Test
    @DisplayName("Should pad milliseconds with zeros")
    void testMillisecondPadding() throws Exception {
        // Given: DateTime with 1 millisecond
        OffsetDateTimeSerializer serializer = new OffsetDateTimeSerializer();
        OffsetDateTime dateTime = OffsetDateTime.of(
            2025, 1, 27, 10, 30, 45, 1_000_000, // 1 millisecond
            ZoneOffset.ofHours(1)
        );

        StringWriter writer = new StringWriter();
        JsonGenerator jsonGenerator = new JsonFactory().createGenerator(writer);
        SerializerProvider serializerProvider = null;

        // When
        serializer.serialize(dateTime, jsonGenerator, serializerProvider);
        jsonGenerator.flush();

        // Then: Should be .001 not .1
        String result = writer.toString();
        assertThat(result).contains(".001");
    }
}
