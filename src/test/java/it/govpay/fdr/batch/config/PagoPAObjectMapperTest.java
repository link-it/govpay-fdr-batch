package it.govpay.fdr.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the pagoPA API Jackson 3 mapper configuration.
 * Exercises the real {@link FdrApiClientConfig#createPagoPAObjectMapper()} with:
 * - Fixed 3-digit milliseconds format for serialization (without timezone)
 * - Flexible variable milliseconds format for deserialization (security)
 */
class PagoPAObjectMapperTest {

    private JsonMapper pagoPAObjectMapper;

    @BeforeEach
    void setUp() {
        FdrApiClientConfig config = new FdrApiClientConfig();
        ReflectionTestUtils.setField(config, "timezone", "Europe/Rome");
        pagoPAObjectMapper = config.createPagoPAObjectMapper();
    }

    @Test
    @DisplayName("Should serialize OffsetDateTime WITHOUT timezone (SSS format)")
    void testSerializeWithoutTimezone() throws JacksonException {
        OffsetDateTime dateTime = OffsetDateTime.of(2025, 1, 27, 10, 30, 45, 123_000_000, ZoneOffset.ofHours(1));
        String json = pagoPAObjectMapper.writeValueAsString(dateTime);
        assertThat(json).contains("2025-01-27T");
        assertThat(json).contains(":30:45.");
    }

    @Test
    @DisplayName("Should deserialize dates with variable milliseconds from pagoPA")
    void testDeserializeVariableMillisFromPagoPA() throws JacksonException {
        OffsetDateTime result1 = pagoPAObjectMapper.readValue("\"2025-01-27T10:30:45.1\"", OffsetDateTime.class);
        OffsetDateTime result2 = pagoPAObjectMapper.readValue("\"2025-01-27T10:30:45.12\"", OffsetDateTime.class);
        OffsetDateTime result3 = pagoPAObjectMapper.readValue("\"2025-01-27T10:30:45.123\"", OffsetDateTime.class);
        OffsetDateTime result6 = pagoPAObjectMapper.readValue("\"2025-01-27T10:30:45.123456\"", OffsetDateTime.class);
        OffsetDateTime result9 = pagoPAObjectMapper.readValue("\"2025-01-27T10:30:45.123456789\"", OffsetDateTime.class);

        assertThat(result1.getNano()).isEqualTo(100_000_000);
        assertThat(result2.getNano()).isEqualTo(120_000_000);
        assertThat(result3.getNano()).isEqualTo(123_000_000);
        assertThat(result6.getNano()).isEqualTo(123_456_000);
        assertThat(result9.getNano()).isEqualTo(123_456_789);
    }

    @Test
    @DisplayName("Should deserialize pagoPA dates without milliseconds")
    void testDeserializePagoPAWithoutMillis() throws JacksonException {
        OffsetDateTime result = pagoPAObjectMapper.readValue("\"2025-01-27T10:30:45\"", OffsetDateTime.class);
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
    void testCETFallbackForPagoPADates() throws JacksonException {
        OffsetDateTime result = pagoPAObjectMapper.readValue("\"2025-01-27T10:30:45.123\"", OffsetDateTime.class);
        assertThat(result).isNotNull();
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(1, 0));
    }

    @Test
    @DisplayName("PagoPAObjectMapper handles explicit timezone with flexible fallback")
    void testDeserializePagoPAWithTimezoneUsingFlexibleFallback() throws JacksonException {
        OffsetDateTime result = pagoPAObjectMapper.readValue("\"2025-01-27T10:30:45.123+01:00\"", OffsetDateTime.class);
        assertThat(result).isNotNull();
        assertThat(result.getYear()).isEqualTo(2025);
        assertThat(result.getNano()).isEqualTo(123_000_000);
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.ofHours(1));
    }

    @Test
    @DisplayName("Should handle mixed precision pagoPA responses")
    void testMixedPrecisionPagoPAResponses() throws JacksonException {
        assertThat(pagoPAObjectMapper.readValue("\"2025-01-27T10:30:45\"", OffsetDateTime.class).getNano()).isEqualTo(0);
        assertThat(pagoPAObjectMapper.readValue("\"2025-01-27T10:30:45.1\"", OffsetDateTime.class).getNano()).isEqualTo(100_000_000);
        assertThat(pagoPAObjectMapper.readValue("\"2025-01-27T10:30:45.123\"", OffsetDateTime.class).getNano()).isEqualTo(123_000_000);
        assertThat(pagoPAObjectMapper.readValue("\"2025-01-27T10:30:45.123456789\"", OffsetDateTime.class).getNano()).isEqualTo(123_456_789);
    }

    @Test
    @DisplayName("Should handle realistic pagoPA SingleFlowResponse date fields")
    void testRealisticPagoPADateFields() throws JacksonException {
        String json = """
            {
                "fdr": "2025-01-27PSP001-0001",
                "fdrDate": "2025-01-27T10:30:45.123",
                "published": "2025-01-27T11:00:00.456789",
                "created": "2025-01-26T15:20:10.1",
                "updated": "2025-01-27T09:45:30"
            }
            """;

        PagoPAFlowResponse result = pagoPAObjectMapper.readValue(json, PagoPAFlowResponse.class);

        assertThat(result.fdr).isEqualTo("2025-01-27PSP001-0001");
        assertThat(result.fdrDate).isNotNull();
        assertThat(result.fdrDate.getNano()).isEqualTo(123_000_000);
        assertThat(result.published).isNotNull();
        assertThat(result.published.getNano()).isEqualTo(456_789_000);
        assertThat(result.created).isNotNull();
        assertThat(result.created.getNano()).isEqualTo(100_000_000);
        assertThat(result.updated).isNotNull();
        assertThat(result.updated.getNano()).isEqualTo(0);
        assertThat(result.fdrDate.getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(1, 0));
        assertThat(result.published.getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(1, 0));
    }

    @Test
    @DisplayName("Enum serialization should use toString()")
    void testEnumSerialization() throws JacksonException {
        TestPayment payment = new TestPayment();
        payment.status = PaymentStatus.EXECUTED;
        String json = pagoPAObjectMapper.writeValueAsString(payment);
        assertThat(json).contains("\"EXECUTED\"");
    }

    @Test
    @DisplayName("Enum deserialization should use toString()")
    void testEnumDeserialization() throws JacksonException {
        TestPayment result = pagoPAObjectMapper.readValue("{\"status\":\"EXECUTED\"}", TestPayment.class);
        assertThat(result.status).isEqualTo(PaymentStatus.EXECUTED);
    }

    /** Helper class for testing pagoPA flow response */
    static class PagoPAFlowResponse {
        public String fdr;
        public OffsetDateTime fdrDate;
        public OffsetDateTime published;
        public OffsetDateTime created;
        public OffsetDateTime updated;
    }

    /** Helper class for enum testing */
    static class TestPayment {
        public PaymentStatus status;
    }

    /** Helper enum for testing */
    enum PaymentStatus {
        EXECUTED,
        REVOKED,
        STAND_IN
    }
}
