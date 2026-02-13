package it.govpay.fdr.batch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import it.govpay.common.client.service.ConnettoreService;
import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.utils.LocalDateFlexibleDeserializer;
import it.govpay.fdr.batch.utils.OffsetDateTimeDeserializer;
import it.govpay.fdr.batch.utils.OffsetDateTimeSerializer;
import lombok.extern.slf4j.Slf4j;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.TimeZone;

/**
 * Configuration for FDR API client.
 * Uses ConnettoreService to obtain a RestTemplate pre-configured with URL, auth, timeouts and GDE interceptor.
 * Customizes only the ObjectMapper for pagoPA date handling.
 */
@Slf4j
@Configuration
public class FdrApiClientConfig {

    private final ConnettoreService connettoreService;
    private final BatchProperties batchProperties;

    @Value("${spring.jackson.time-zone:Europe/Rome}")
    private String timezone;

    public FdrApiClientConfig(ConnettoreService connettoreService, BatchProperties batchProperties) {
        this.connettoreService = connettoreService;
        this.batchProperties = batchProperties;
    }

    @Bean
    public RestTemplate fdrApiRestTemplate() {
        RestTemplate restTemplate = connettoreService.getRestTemplate(batchProperties.getConnettorePagopaFdr());

        // Configure custom ObjectMapper for secure date handling from pagoPA API
        // Remove default Jackson converter and add our custom one
        ObjectMapper objectMapper = createPagoPAObjectMapper();
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);
        restTemplate.getMessageConverters().removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
        restTemplate.getMessageConverters().add(0, converter);

        return restTemplate;
    }

    /**
     * Creates a custom ObjectMapper for pagoPA API client with enhanced date handling security.
     * <p>
     * Configuration:
     * - Serialization: uses fixed format yyyy-MM-dd'T'HH:mm:ss.SSS
     * - Deserialization: accepts variable-length milliseconds (1-9 digits) for security
     * - Fallback: if timezone is missing, defaults to CET
     * - Dates: written as ISO-8601 strings (not timestamps) with zone ID
     * - Timezone: configured from spring.jackson.time-zone property
     *
     * @return configured ObjectMapper for pagoPA API
     */
    private ObjectMapper createPagoPAObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        // Set timezone from configuration
        objectMapper.setTimeZone(TimeZone.getTimeZone(timezone));

        // Set date format for java.util.Date (legacy support)
        objectMapper.setDateFormat(
            new SimpleDateFormat(Costanti.PATTERN_DATA_JSON_YYYY_MM_DD_T_HH_MM_SS_SSS)
        );

        // Register Java Time Module with custom serializers
        // Serializer: fixed 3-digit milliseconds format for outgoing requests
        // Deserializer: flexible format accepting variable milliseconds from pagoPA responses
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addSerializer(
            OffsetDateTime.class,
            new OffsetDateTimeSerializer(Costanti.PATTERN_DATA_JSON_YYYY_MM_DD_T_HH_MM_SS_SSS)
        );
        javaTimeModule.addDeserializer(
            OffsetDateTime.class,
            new OffsetDateTimeDeserializer(Costanti.PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX)
        );
        // LocalDate deserializer: handles both date and datetime formats from pagoPA
        javaTimeModule.addDeserializer(
            LocalDate.class,
            new LocalDateFlexibleDeserializer()
        );

        objectMapper.registerModule(javaTimeModule);

        // Configure enum and date serialization
        objectMapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
        objectMapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
        objectMapper.enable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return objectMapper;
    }
}
