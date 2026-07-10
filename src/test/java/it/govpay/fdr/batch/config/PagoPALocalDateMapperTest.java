package it.govpay.fdr.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end tests for the pagoPA Jackson 3 mapper deserialization of LocalDate fields
 * (issue #42), exercising the real {@link FdrApiClientConfig#createPagoPAObjectMapper()}.
 */
class PagoPALocalDateMapperTest {

    private JsonMapper pagoPAObjectMapper;

    @BeforeEach
    void setUp() {
        FdrApiClientConfig config = new FdrApiClientConfig();
        ReflectionTestUtils.setField(config, "timezone", "Europe/Rome");
        pagoPAObjectMapper = config.createPagoPAObjectMapper();
    }

    @Test
    @DisplayName("regulationDate nuovo formato pagoPA (date): \"1970-01-01\"")
    void testDeserializeRegulationDateNewFormat() throws JacksonException {
        String json = """
            {
                "fdr": "1970-01-01PSP001-0001",
                "regulationDate": "1970-01-01"
            }
            """;

        PagoPARegulationResponse result = pagoPAObjectMapper.readValue(json, PagoPARegulationResponse.class);

        assertThat(result.fdr).isEqualTo("1970-01-01PSP001-0001");
        assertThat(result.regulationDate).isEqualTo(LocalDate.of(1970, 1, 1));
    }

    @Test
    @DisplayName("regulationDate vecchio formato pagoPA (date-time): \"1970-01-01T00:00:00.000000+02:00\"")
    void testDeserializeRegulationDateLegacyFormat() throws JacksonException {
        String json = """
            {
                "fdr": "1970-01-01PSP001-0001",
                "regulationDate": "1970-01-01T00:00:00.000000+02:00"
            }
            """;

        PagoPARegulationResponse result = pagoPAObjectMapper.readValue(json, PagoPARegulationResponse.class);

        assertThat(result.fdr).isEqualTo("1970-01-01PSP001-0001");
        assertThat(result.regulationDate).isEqualTo(LocalDate.of(1970, 1, 1));
    }

    @Test
    @DisplayName("Payload misto: regulationDate (LocalDate) e fdrDate (OffsetDateTime) insieme")
    void testDeserializeMixedDateFields() throws JacksonException {
        String json = """
            {
                "fdr": "2025-01-27PSP001-0001",
                "regulationDate": "2025-01-27",
                "fdrDate": "2025-01-27T10:30:45.123+01:00"
            }
            """;

        PagoPARegulationResponse result = pagoPAObjectMapper.readValue(json, PagoPARegulationResponse.class);

        assertThat(result.regulationDate).isEqualTo(LocalDate.of(2025, 1, 27));
        assertThat(result.fdrDate).isNotNull();
        assertThat(result.fdrDate.getYear()).isEqualTo(2025);
        assertThat(result.fdrDate.getNano()).isEqualTo(123_000_000);
        assertThat(result.fdrDate.getOffset()).isEqualTo(ZoneOffset.ofHours(1));
    }

    /**
     * Helper POJO che modella la porzione di Flow rilevante per la issue #42:
     * un campo {@link LocalDate} (regulationDate) e un campo {@link OffsetDateTime}
     * (fdrDate) usato per verificare la coesistenza dei due deserializer.
     */
    static class PagoPARegulationResponse {
        public String fdr;
        public LocalDate regulationDate;
        public OffsetDateTime fdrDate;
    }
}
