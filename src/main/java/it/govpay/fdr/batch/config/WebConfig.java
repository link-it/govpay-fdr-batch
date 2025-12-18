package it.govpay.fdr.batch.config;

import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.TimeZone;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.utils.OffsetDateTimeDeserializer;
import it.govpay.fdr.batch.utils.OffsetDateTimeSerializer;

/**
 * Web configuration class that provides a global ObjectMapper bean
 * with custom date serialization/deserialization for the entire application.
 * <p>
 * This ObjectMapper is used by:
 * - GDE client for sending events with customized date formats
 * - Spring MVC for JSON request/response handling
 * - Any component that requires JSON processing
 */
@Configuration
public class WebConfig {

	@Value("${spring.jackson.time-zone:Europe/Rome}")
	private String timezone;

	/**
	 * Creates a global ObjectMapper bean with custom configurations for date handling.
	 * <p>
	 * Configuration details:
	 * - Date format: yyyy-MM-dd'T'HH:mm:ss.SSSXXX (timestamp with timezone)
	 * - Custom OffsetDateTime serializer: ensures consistent date format in output
	 * - Custom OffsetDateTime deserializer: handles variable milliseconds from pagoPA API
	 * - Enums: serialized/deserialized using toString()
	 * - Dates: written as ISO-8601 strings (not timestamps) with zone ID
	 * - Timezone: configured from spring.jackson.time-zone property
	 *
	 * @return configured ObjectMapper instance
	 */
	@Bean
	public ObjectMapper objectMapper() {
		ObjectMapper objectMapper = new ObjectMapper();

		// Set timezone from configuration
		objectMapper.setTimeZone(TimeZone.getTimeZone(timezone));

		// Set date format for java.util.Date (legacy support)
		objectMapper.setDateFormat(
			new SimpleDateFormat(Costanti.PATTERN_TIMESTAMP_3_YYYY_MM_DD_T_HH_MM_SS_SSSXXX)
		);

		// Enable enum serialization using toString()
		objectMapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
		objectMapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);

		// Configure date serialization format
		objectMapper.enable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID);
		objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

		// Register Java Time Module with custom serializers for OffsetDateTime
		JavaTimeModule javaTimeModule = new JavaTimeModule();
		javaTimeModule.addSerializer(OffsetDateTime.class, new OffsetDateTimeSerializer());
		javaTimeModule.addDeserializer(OffsetDateTime.class, new OffsetDateTimeDeserializer());
		objectMapper.registerModule(javaTimeModule);

		return objectMapper;
	}
}
