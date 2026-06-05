package it.govpay.fdr.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
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
import it.govpay.fdr.batch.utils.LocalDateFlexibleDeserializer;
import it.govpay.fdr.batch.utils.OffsetDateTimeDeserializer;
import it.govpay.fdr.batch.utils.OffsetDateTimeSerializer;

/**
 * End-to-end tests for the pagoPA ObjectMapper deserialization of LocalDate fields
 * (e.g. {@code regulationDate}).
 * <p>
 * Verifica che il pipeline Jackson configurato in
 * {@link it.govpay.fdr.batch.config.FdrApiClientConfig#createPagoPAObjectMapper()}
 * accetti, per i campi {@link LocalDate}, sia il nuovo formato {@code date}
 * (es. {@code "1970-01-01"}) sia il vecchio formato {@code date-time}
 * (es. {@code "1970-01-01T00:00:00.000000+02:00"}), in conformità alla
 * comunicazione pagoPA (issue #42).
 */
class PagoPALocalDateMapperTest {

    private ObjectMapper pagoPAObjectMapper;

    @BeforeEach
    void setUp() {
        // Replica integrale di FdrApiClientConfig.createPagoPAObjectMapper()
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
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX)
        );
        javaTimeModule.addDeserializer(
            LocalDate.class,
            new LocalDateFlexibleDeserializer()
        );

        pagoPAObjectMapper.registerModule(javaTimeModule);

        pagoPAObjectMapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
        pagoPAObjectMapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
        pagoPAObjectMapper.enable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID);
        pagoPAObjectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    @DisplayName("regulationDate nuovo formato pagoPA (date): \"1970-01-01\"")
    void testDeserializeRegulationDateNewFormat() throws JsonProcessingException {
        // Esempio letterale dalla comunicazione pagoPA (issue #42)
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
    void testDeserializeRegulationDateLegacyFormat() throws JsonProcessingException {
        // Esempio letterale dalla comunicazione pagoPA (issue #42)
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
    void testDeserializeMixedDateFields() throws JsonProcessingException {
        // Scenario realistico: nel Flow di pagoPA convivono LocalDate (regulationDate)
        // e OffsetDateTime (fdrDate). Verifica che entrambi i deserializer
        // siano cablati correttamente sullo stesso ObjectMapper.
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
