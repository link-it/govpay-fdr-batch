package it.govpay.fdr.batch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration tests for global ObjectMapper configuration (WebConfig).
 * Tests serialization and deserialization of dates with custom formats.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.batch.job.enabled=false",
    "govpay.gde.enabled=false"
})
class ObjectMapperConfigTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("ObjectMapper bean should be autowired from WebConfig")
    void testObjectMapperBeanExists() {
        assertThat(objectMapper).isNotNull();
    }

    @Test
    @DisplayName("Should serialize OffsetDateTime with timezone (SSSXXX format)")
    void testSerializeOffsetDateTimeWithTimezone() throws JsonProcessingException {
        // Given: A specific date/time with CET timezone
        OffsetDateTime dateTime = OffsetDateTime.of(
            2025, 1, 27, 10, 30, 45, 123_000_000,
            ZoneOffset.ofHours(1)
        );

        // When: Serialize to JSON
        String json = objectMapper.writeValueAsString(dateTime);

        // Then: Should be in format yyyy-MM-dd'T'HH:mm:ss.SSSXXX
        assertThat(json).contains("2025-01-27T10:30:45.123+01:00");
    }

    @Test
    @DisplayName("Should deserialize date with 3-digit milliseconds and timezone")
    void testDeserializeWithThreeDigitMillis() throws JsonProcessingException {
        // Given: JSON with 3-digit milliseconds
        String json = "\"2025-01-27T10:30:45.123+01:00\"";

        // When: Deserialize from JSON
        OffsetDateTime result = objectMapper.readValue(json, OffsetDateTime.class);

        // Then: Should parse correctly
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
    @DisplayName("Global ObjectMapper uses fixed 3-digit milliseconds pattern")
    void testGlobalObjectMapperUsesFixedPattern() throws JsonProcessingException {
        // Given: JSON with exactly 3-digit milliseconds (fixed pattern)
        String json = "\"2025-01-27T10:30:45.123+01:00\"";

        // When: Deserialize from JSON
        OffsetDateTime result = objectMapper.readValue(json, OffsetDateTime.class);

        // Then: Should parse correctly with 123ms
        assertThat(result).isNotNull();
        assertThat(result.getNano()).isEqualTo(123_000_000);
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(1));
    }

    @Test
    @DisplayName("Global ObjectMapper requires timezone in fixed pattern")
    void testGlobalObjectMapperRequiresTimezone() {
        // Given: JSON without timezone (fixed pattern requires timezone)
        String json = "\"2025-01-27T10:30:45.123\"";

        // When/Then: Should throw exception as pattern requires timezone
        assertThatThrownBy(() -> objectMapper.readValue(json, OffsetDateTime.class))
            .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    @DisplayName("Should handle UTC timezone (+00:00)")
    void testDeserializeWithUTCTimezone() throws JsonProcessingException {
        // Given: JSON with UTC timezone
        String json = "\"2025-01-27T10:30:45.123Z\"";

        // When: Deserialize from JSON
        OffsetDateTime result = objectMapper.readValue(json, OffsetDateTime.class);

        // Then: Should parse correctly with UTC offset
        assertThat(result).isNotNull();
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("Should handle different timezone offsets")
    void testDeserializeWithDifferentTimezones() throws JsonProcessingException {
        // Given: JSON with different timezone offsets
        String jsonCET = "\"2025-01-27T10:30:45.123+01:00\"";
        String jsonEST = "\"2025-01-27T10:30:45.123-05:00\"";
        String jsonJST = "\"2025-01-27T10:30:45.123+09:00\"";

        // When: Deserialize all
        OffsetDateTime resultCET = objectMapper.readValue(jsonCET, OffsetDateTime.class);
        OffsetDateTime resultEST = objectMapper.readValue(jsonEST, OffsetDateTime.class);
        OffsetDateTime resultJST = objectMapper.readValue(jsonJST, OffsetDateTime.class);

        // Then: Should parse with correct offsets
        assertThat(resultCET.getOffset()).isEqualTo(ZoneOffset.ofHours(1));
        assertThat(resultEST.getOffset()).isEqualTo(ZoneOffset.ofHours(-5));
        assertThat(resultJST.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    @DisplayName("Should handle null OffsetDateTime serialization")
    void testSerializeNullOffsetDateTime() throws JsonProcessingException {
        // Given: A null OffsetDateTime
        OffsetDateTime nullDateTime = null;

        // When: Serialize to JSON
        String json = objectMapper.writeValueAsString(nullDateTime);

        // Then: Should be "null"
        assertThat(json).isEqualTo("null");
    }

    @Test
    @DisplayName("Should handle null string deserialization")
    void testDeserializeNullString() throws JsonProcessingException {
        // Given: A JSON null
        String json = "null";

        // When: Deserialize from JSON
        OffsetDateTime result = objectMapper.readValue(json, OffsetDateTime.class);

        // Then: Should be null
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should handle empty string gracefully")
    void testDeserializeEmptyString() throws JsonProcessingException {
        // Given: An empty JSON string
        String json = "\"\"";

        // When: Deserialize from JSON
        OffsetDateTime result = objectMapper.readValue(json, OffsetDateTime.class);

        // Then: Should be null (empty string treated as null)
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should throw exception for invalid date format")
    void testDeserializeInvalidFormat() {
        // Given: Invalid date format
        String json = "\"2025-27-01T10:30:45.123+01:00\""; // Invalid month/day

        // When/Then: Should throw exception
        assertThatThrownBy(() -> objectMapper.readValue(json, OffsetDateTime.class))
            .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    @DisplayName("Should maintain precision in round-trip serialization")
    void testRoundTripSerialization() throws JsonProcessingException {
        // Given: Original datetime
        OffsetDateTime original = OffsetDateTime.of(
            2025, 1, 27, 10, 30, 45, 123_000_000,
            ZoneOffset.ofHours(1)
        );

        // When: Serialize and deserialize
        String json = objectMapper.writeValueAsString(original);
        OffsetDateTime result = objectMapper.readValue(json, OffsetDateTime.class);

        // Then: Should be equal (within millisecond precision)
        assertThat(result.toInstant()).isEqualTo(original.toInstant());
        assertThat(result.getOffset()).isEqualTo(original.getOffset());
    }

    @Test
    @DisplayName("Should handle complex object with OffsetDateTime field")
    void testDeserializeComplexObjectWithDateTime() throws JsonProcessingException {
        // Given: JSON with nested OffsetDateTime
        String json = """
            {
                "name": "Test Event",
                "timestamp": "2025-01-27T10:30:45.123+01:00"
            }
            """;

        // When: Deserialize to TestEvent
        TestEvent result = objectMapper.readValue(json, TestEvent.class);

        // Then: Should parse correctly
        assertThat(result.name).isEqualTo("Test Event");
        assertThat(result.timestamp).isNotNull();
        assertThat(result.timestamp.getYear()).isEqualTo(2025);
        assertThat(result.timestamp.getNano()).isEqualTo(123_000_000);
    }

    /**
     * Helper class for testing complex object serialization
     */
    static class TestEvent {
        public String name;
        public OffsetDateTime timestamp;
    }
}
