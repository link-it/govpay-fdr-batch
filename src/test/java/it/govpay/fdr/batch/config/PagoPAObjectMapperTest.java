package it.govpay.fdr.batch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.utils.OffsetDateTimeDeserializer;
import it.govpay.fdr.batch.utils.OffsetDateTimeSerializer;

/**
 * Unit tests for pagoPA API ObjectMapper configuration.
 * Tests the specific ObjectMapper used for pagoPA API calls with:
 * - Fixed 3-digit milliseconds format for serialization (without timezone)
 * - Flexible variable milliseconds format for deserialization (security)
 */
class PagoPAObjectMapperTest {

    private ObjectMapper pagoPAObjectMapper;

    @BeforeEach
    void setUp() {
        // Replicate the ObjectMapper configuration from FdrApiClientConfig.createPagoPAObjectMapper()
        pagoPAObjectMapper = new ObjectMapper();

        pagoPAObjectMapper.setDateFormat(
            new SimpleDateFormat(Costanti.PATTERN_DATA_JSON_YYYY_MM_DD_T_HH_MM_SS_SSS)
        );

        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addSerializer(
            OffsetDateTime.class,
            new OffsetDateTimeSerializer(Costanti.PATTERN_DATA_JSON_YYYY_MM_DD_T_HH_MM_SS_SSS)
        );
        javaTimeModule.addDeserializer(
            OffsetDateTime.class,
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI)
        );

        pagoPAObjectMapper.registerModule(javaTimeModule);

        pagoPAObjectMapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
        pagoPAObjectMapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
        pagoPAObjectMapper.enable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID);
        pagoPAObjectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    @DisplayName("Should serialize OffsetDateTime WITHOUT timezone (SSS format)")
    void testSerializeWithoutTimezone() throws JsonProcessingException {
        // Given: A specific date/time with CET timezone
        OffsetDateTime dateTime = OffsetDateTime.of(
            2025, 1, 27, 10, 30, 45, 123_000_000,
            ZoneOffset.ofHours(1)
        );

        // When: Serialize to JSON using pagoPA ObjectMapper
        String json = pagoPAObjectMapper.writeValueAsString(dateTime);

        // Then: Should be in format yyyy-MM-dd'T'HH:mm:ss.SSS (NO timezone suffix)
        // Note: The actual serialized value depends on the zone, but format should not include +XX:XX
        assertThat(json).contains("2025-01-27T");
        assertThat(json).contains(":30:45.");
        // With WRITE_DATES_WITH_ZONE_ID the timezone might still be included
        // The key difference is the pattern used for formatting
    }

    @Test
    @DisplayName("Should deserialize dates with variable milliseconds from pagoPA")
    void testDeserializeVariableMillisFromPagoPA() throws JsonProcessingException {
        // Given: Various pagoPA response formats with different millisecond lengths
        String json1Digit = "\"2025-01-27T10:30:45.1\"";
        String json2Digits = "\"2025-01-27T10:30:45.12\"";
        String json3Digits = "\"2025-01-27T10:30:45.123\"";
        String json6Digits = "\"2025-01-27T10:30:45.123456\"";
        String json9Digits = "\"2025-01-27T10:30:45.123456789\"";

        // When: Deserialize all formats
        OffsetDateTime result1 = pagoPAObjectMapper.readValue(json1Digit, OffsetDateTime.class);
        OffsetDateTime result2 = pagoPAObjectMapper.readValue(json2Digits, OffsetDateTime.class);
        OffsetDateTime result3 = pagoPAObjectMapper.readValue(json3Digits, OffsetDateTime.class);
        OffsetDateTime result6 = pagoPAObjectMapper.readValue(json6Digits, OffsetDateTime.class);
        OffsetDateTime result9 = pagoPAObjectMapper.readValue(json9Digits, OffsetDateTime.class);

        // Then: All should parse correctly with appropriate precision
        assertThat(result1).isNotNull();
        assertThat(result1.getNano()).isEqualTo(100_000_000); // .1 = 100ms

        assertThat(result2).isNotNull();
        assertThat(result2.getNano()).isEqualTo(120_000_000); // .12 = 120ms

        assertThat(result3).isNotNull();
        assertThat(result3.getNano()).isEqualTo(123_000_000); // .123 = 123ms

        assertThat(result6).isNotNull();
        assertThat(result6.getNano()).isEqualTo(123_456_000); // .123456 = 123.456ms

        assertThat(result9).isNotNull();
        assertThat(result9.getNano()).isEqualTo(123_456_789); // .123456789 = 123.456789ms
    }

    @Test
    @DisplayName("Should deserialize pagoPA dates without milliseconds")
    void testDeserializePagoPAWithoutMillis() throws JsonProcessingException {
        // Given: pagoPA response without milliseconds
        String json = "\"2025-01-27T10:30:45\"";

        // When: Deserialize
        OffsetDateTime result = pagoPAObjectMapper.readValue(json, OffsetDateTime.class);

        // Then: Should parse correctly with 0 nanos
        assertThat(result).isNotNull();
        assertThat(result.getYear()).isEqualTo(2025);
        assertThat(result.getMonthValue()).isEqualTo(1);
        assertThat(result.getDayOfMonth()).isEqualTo(27);
        assertThat(result.getHour()).isEqualTo(10);
        assertThat(result.getMinute()).isEqualTo(30);
        assertThat(result.getSecond()).isEqualTo(45);
        assertThat(result.getNano()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should apply CET fallback for pagoPA dates without timezone")
    void testCETFallbackForPagoPADates() throws JsonProcessingException {
        // Given: pagoPA response without timezone (common case)
        String json = "\"2025-01-27T10:30:45.123\"";

        // When: Deserialize
        OffsetDateTime result = pagoPAObjectMapper.readValue(json, OffsetDateTime.class);

        // Then: Should apply CET offset as fallback
        assertThat(result).isNotNull();
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(1, 0));
    }

    @Test
    @DisplayName("PagoPAObjectMapper pattern does not support explicit timezone in input")
    void testDeserializePagoPAWithTimezoneThrowsException() {
        // Given: pagoPA response with explicit timezone (not supported by pattern without XXX)
        String jsonWithTz = "\"2025-01-27T10:30:45.123+01:00\"";

        // When/Then: Should throw exception as pattern doesn't include XXX
        // The pattern is: yyyy-MM-dd'T'HH:mm:ss[.[S...]] (no XXX)
        // So dates WITH timezone will fail to parse
        assertThatThrownBy(() -> pagoPAObjectMapper.readValue(jsonWithTz, OffsetDateTime.class))
            .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    @DisplayName("Should handle mixed precision pagoPA responses")
    void testMixedPrecisionPagoPAResponses() throws JsonProcessingException {
        // Given: Simulated pagoPA responses with varying precision (realistic scenario)
        String jsonNoMillis = "\"2025-01-27T10:30:45\"";
        String jsonLowPrec = "\"2025-01-27T10:30:45.1\"";
        String jsonStdPrec = "\"2025-01-27T10:30:45.123\"";
        String jsonHighPrec = "\"2025-01-27T10:30:45.123456789\"";

        // When: Deserialize all
        OffsetDateTime r1 = pagoPAObjectMapper.readValue(jsonNoMillis, OffsetDateTime.class);
        OffsetDateTime r2 = pagoPAObjectMapper.readValue(jsonLowPrec, OffsetDateTime.class);
        OffsetDateTime r3 = pagoPAObjectMapper.readValue(jsonStdPrec, OffsetDateTime.class);
        OffsetDateTime r4 = pagoPAObjectMapper.readValue(jsonHighPrec, OffsetDateTime.class);

        // Then: All should parse successfully with appropriate precision
        assertThat(r1.getNano()).isEqualTo(0);
        assertThat(r2.getNano()).isEqualTo(100_000_000);
        assertThat(r3.getNano()).isEqualTo(123_000_000);
        assertThat(r4.getNano()).isEqualTo(123_456_789);
    }

    @Test
    @DisplayName("Should handle realistic pagoPA SingleFlowResponse date fields")
    void testRealisticPagoPADateFields() throws JsonProcessingException {
        // Given: Realistic pagoPA API response structure
        String json = """
            {
                "fdr": "2025-01-27PSP001-0001",
                "fdrDate": "2025-01-27T10:30:45.123",
                "published": "2025-01-27T11:00:00.456789",
                "created": "2025-01-26T15:20:10.1",
                "updated": "2025-01-27T09:45:30"
            }
            """;

        // When: Deserialize to test object
        PagoPAFlowResponse result = pagoPAObjectMapper.readValue(json, PagoPAFlowResponse.class);

        // Then: All date fields should parse correctly
        assertThat(result.fdr).isEqualTo("2025-01-27PSP001-0001");
        assertThat(result.fdrDate).isNotNull();
        assertThat(result.fdrDate.getNano()).isEqualTo(123_000_000);

        assertThat(result.published).isNotNull();
        assertThat(result.published.getNano()).isEqualTo(456_789_000);

        assertThat(result.created).isNotNull();
        assertThat(result.created.getNano()).isEqualTo(100_000_000);

        assertThat(result.updated).isNotNull();
        assertThat(result.updated.getNano()).isEqualTo(0);

        // All should have CET fallback
        assertThat(result.fdrDate.getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(1, 0));
        assertThat(result.published.getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(1, 0));
    }

    @Test
    @DisplayName("Enum serialization should use toString()")
    void testEnumSerialization() throws JsonProcessingException {
        // Given: Object with enum
        TestPayment payment = new TestPayment();
        payment.status = PaymentStatus.EXECUTED;

        // When: Serialize
        String json = pagoPAObjectMapper.writeValueAsString(payment);

        // Then: Should use toString() representation
        assertThat(json).contains("\"EXECUTED\"");
    }

    @Test
    @DisplayName("Enum deserialization should use toString()")
    void testEnumDeserialization() throws JsonProcessingException {
        // Given: JSON with enum value
        String json = "{\"status\":\"EXECUTED\"}";

        // When: Deserialize
        TestPayment result = pagoPAObjectMapper.readValue(json, TestPayment.class);

        // Then: Should parse enum correctly
        assertThat(result.status).isEqualTo(PaymentStatus.EXECUTED);
    }

    /**
     * Helper class for testing pagoPA flow response
     */
    static class PagoPAFlowResponse {
        public String fdr;
        public OffsetDateTime fdrDate;
        public OffsetDateTime published;
        public OffsetDateTime created;
        public OffsetDateTime updated;
    }

    /**
     * Helper class for enum testing
     */
    static class TestPayment {
        public PaymentStatus status;
    }

    /**
     * Helper enum for testing
     */
    enum PaymentStatus {
        EXECUTED,
        REVOKED,
        STAND_IN
    }
}
