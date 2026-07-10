package it.govpay.fdr.batch.config;

import java.time.OffsetDateTime;
import java.util.TimeZone;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.module.SimpleModule;

import it.govpay.fdr.batch.utils.jackson3.OffsetDateTimeDeserializer;
import it.govpay.fdr.batch.utils.jackson3.OffsetDateTimeSerializer;

/**
 * Personalizza il JsonMapper (Jackson 3) autoconfigurato da Spring Boot, usato
 * globalmente dall'applicazione (Spring MVC, client GDE, qualsiasi componente che
 * serializza/deserializza JSON tramite l'ObjectMapper di default).
 * <p>
 * Configurazione:
 * <ul>
 *   <li>timezone da {@code spring.jackson.time-zone} (default Europe/Rome)</li>
 *   <li>enum serializzati/deserializzati tramite {@code toString()}</li>
 *   <li>OffsetDateTime con formato coerente (yyyy-MM-dd'T'HH:mm:ss.SSSXXX) e
 *       deserializzazione flessibile con fallback CET</li>
 * </ul>
 * Nota: il client pagoPA generato usa un proprio ObjectMapper Jackson 2
 * ({@link FdrApiClientConfig}) e non e' influenzato da questa configurazione.
 */
@Configuration
public class WebConfig {

	@Value("${spring.jackson.time-zone:Europe/Rome}")
	private String timezone;

	/**
	 * Applica la gestione date/enum personalizzata al JsonMapper Jackson 3 di default.
	 *
	 * @return il customizer del builder del JsonMapper
	 */
	@Bean
	public JsonMapperBuilderCustomizer pagoPADateJsonMapperCustomizer() {
		return builder -> {
			SimpleModule offsetDateTimeModule = new SimpleModule();
			offsetDateTimeModule.addSerializer(OffsetDateTime.class, new OffsetDateTimeSerializer());
			offsetDateTimeModule.addDeserializer(OffsetDateTime.class, new OffsetDateTimeDeserializer());

			builder
				.defaultTimeZone(TimeZone.getTimeZone(timezone))
				.enable(EnumFeature.READ_ENUMS_USING_TO_STRING, EnumFeature.WRITE_ENUMS_USING_TO_STRING)
				.addModule(offsetDateTimeModule);
		};
	}
}
