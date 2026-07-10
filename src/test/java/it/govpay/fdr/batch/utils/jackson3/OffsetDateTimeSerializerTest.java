package it.govpay.fdr.batch.utils.jackson3;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import it.govpay.fdr.batch.Costanti;

/**
 * Unit tests for the Jackson 3 OffsetDateTimeSerializer.
 */
class OffsetDateTimeSerializerTest {

    private static JsonMapper mapperWith(OffsetDateTimeSerializer serializer) {
        SimpleModule module = new SimpleModule();
        module.addSerializer(OffsetDateTime.class, serializer);
        return JsonMapper.builder().addModule(module).build();
    }

    @Test
    @DisplayName("Should serialize OffsetDateTime with default format (SSSXXX)")
    void testSerializeWithDefaultFormat() {
        JsonMapper mapper = mapperWith(new OffsetDateTimeSerializer());
        OffsetDateTime dateTime = OffsetDateTime.of(2025, 1, 27, 10, 30, 45, 123_000_000, ZoneOffset.ofHours(1));
        assertThat(mapper.writeValueAsString(dateTime)).isEqualTo("\"2025-01-27T10:30:45.123+01:00\"");
    }

    @Test
    @DisplayName("Should serialize OffsetDateTime with custom format (SSS)")
    void testSerializeWithCustomFormat() {
        JsonMapper mapper = mapperWith(new OffsetDateTimeSerializer(Costanti.PATTERN_DATA_JSON_YYYY_MM_DD_T_HH_MM_SS_SSS));
        OffsetDateTime dateTime = OffsetDateTime.of(2025, 1, 27, 10, 30, 45, 123_000_000, ZoneOffset.ofHours(1));
        assertThat(mapper.writeValueAsString(dateTime)).isEqualTo("\"2025-01-27T10:30:45.123\"");
    }

    @Test
    @DisplayName("Should serialize null as null")
    void testSerializeNull() {
        JsonMapper mapper = mapperWith(new OffsetDateTimeSerializer());
        OffsetDateTime dateTime = null;
        assertThat(mapper.writeValueAsString(dateTime)).isEqualTo("null");
    }

    @Test
    @DisplayName("Should serialize UTC timezone correctly")
    void testSerializeUTC() {
        JsonMapper mapper = mapperWith(new OffsetDateTimeSerializer());
        OffsetDateTime dateTime = OffsetDateTime.of(2025, 1, 27, 10, 30, 45, 123_000_000, ZoneOffset.UTC);
        assertThat(mapper.writeValueAsString(dateTime)).isEqualTo("\"2025-01-27T10:30:45.123Z\"");
    }

    @Test
    @DisplayName("Should serialize negative timezone offset")
    void testSerializeNegativeOffset() {
        JsonMapper mapper = mapperWith(new OffsetDateTimeSerializer());
        OffsetDateTime dateTime = OffsetDateTime.of(2025, 1, 27, 10, 30, 45, 123_000_000, ZoneOffset.ofHours(-5));
        assertThat(mapper.writeValueAsString(dateTime)).isEqualTo("\"2025-01-27T10:30:45.123-05:00\"");
    }

    @Test
    @DisplayName("Should maintain millisecond precision")
    void testMillisecondPrecision() {
        JsonMapper mapper = mapperWith(new OffsetDateTimeSerializer());
        OffsetDateTime dateTime = OffsetDateTime.of(2025, 1, 27, 10, 30, 45, 999_000_000, ZoneOffset.ofHours(1));
        assertThat(mapper.writeValueAsString(dateTime)).contains(".999");
    }

    @Test
    @DisplayName("Should pad milliseconds with zeros")
    void testMillisecondPadding() {
        JsonMapper mapper = mapperWith(new OffsetDateTimeSerializer());
        OffsetDateTime dateTime = OffsetDateTime.of(2025, 1, 27, 10, 30, 45, 1_000_000, ZoneOffset.ofHours(1));
        assertThat(mapper.writeValueAsString(dateTime)).contains(".001");
    }
}
