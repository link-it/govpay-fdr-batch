package it.govpay.fdr.batch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.utils.jackson3.LocalDateFlexibleDeserializer;
import it.govpay.fdr.batch.utils.jackson3.OffsetDateTimeDeserializer;
import it.govpay.fdr.batch.utils.jackson3.OffsetDateTimeSerializer;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.TimeZone;

/**
 * Configuration for FDR API client.
 * Provides the custom Jackson 3 {@link JsonMapper} for pagoPA date handling,
 * used by FdrApiService when creating per-domain RestTemplate instances.
 */
@Slf4j
@Configuration
public class FdrApiClientConfig {

    @Value("${spring.jackson.time-zone:Europe/Rome}")
    private String timezone;

    /**
     * Creates a custom Jackson 3 {@link JsonMapper} for the pagoPA API client with
     * enhanced date handling security.
     * <p>
     * Configuration:
     * - Serialization: fixed format yyyy-MM-dd'T'HH:mm:ss.SSS for OffsetDateTime
     * - Deserialization: accepts variable-length milliseconds (1-9 digits) for security
     * - LocalDate: accepts both date and datetime strings from pagoPA responses
     * - Fallback: if timezone is missing, defaults to CET
     * - Enums: serialized/deserialized using toString()
     * - Dates: written as ISO-8601 strings (not timestamps) with zone ID
     * - Timezone: configured from spring.jackson.time-zone property
     *
     * @return configured Jackson 3 JsonMapper for the pagoPA API
     */
    public JsonMapper createPagoPAObjectMapper() {
        SimpleModule dateModule = new SimpleModule();
        // Serializer: fixed 3-digit milliseconds format for outgoing requests
        dateModule.addSerializer(
            OffsetDateTime.class,
            new OffsetDateTimeSerializer(Costanti.PATTERN_DATA_JSON_YYYY_MM_DD_T_HH_MM_SS_SSS)
        );
        // Deserializer: flexible format accepting variable milliseconds from pagoPA responses
        dateModule.addDeserializer(
            OffsetDateTime.class,
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX)
        );
        // LocalDate deserializer: handles both date and datetime formats from pagoPA
        dateModule.addDeserializer(LocalDate.class, new LocalDateFlexibleDeserializer());

        return JsonMapper.builder()
            .defaultTimeZone(TimeZone.getTimeZone(timezone))
            .addModule(dateModule)
            .enable(EnumFeature.READ_ENUMS_USING_TO_STRING, EnumFeature.WRITE_ENUMS_USING_TO_STRING)
            .enable(DateTimeFeature.WRITE_DATES_WITH_ZONE_ID)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    }
}
