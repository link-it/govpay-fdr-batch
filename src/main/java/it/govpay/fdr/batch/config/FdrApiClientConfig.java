package it.govpay.fdr.batch.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Configuration for FDR API client with authentication
 */
@Configuration
public class FdrApiClientConfig {

    private final PagoPAProperties pagoPAProperties;

    public FdrApiClientConfig(PagoPAProperties pagoPAProperties) {
        this.pagoPAProperties = pagoPAProperties;
    }

    @Bean
    public RestTemplate fdrApiRestTemplate(RestTemplateBuilder builder) {
        return builder
            .rootUri(pagoPAProperties.getBaseUrl())
            .setConnectTimeout(Duration.ofMillis(pagoPAProperties.getConnectionTimeout()))
            .setReadTimeout(Duration.ofMillis(pagoPAProperties.getReadTimeout()))
            .additionalInterceptors(subscriptionKeyInterceptor())
            .build();
    }

    /**
     * Interceptor to add subscription key header to all requests
     */
    private ClientHttpRequestInterceptor subscriptionKeyInterceptor() {
        return (request, body, execution) -> {
            request.getHeaders().add(
                pagoPAProperties.getSubscriptionKeyHeader(),
                pagoPAProperties.getSubscriptionKey()
            );
            return execution.execute(request, body);
        };
    }
}
