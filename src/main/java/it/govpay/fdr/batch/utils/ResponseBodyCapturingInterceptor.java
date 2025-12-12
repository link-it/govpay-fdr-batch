package it.govpay.fdr.batch.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Interceptor that captures raw HTTP response body for GDE logging.
 *
 * This interceptor reads the entire response body, stores it in ResponseBodyHolder,
 * and returns a wrapper response that allows the body to be read again by the
 * RestTemplate message converters.
 *
 * This ensures that if deserialization fails, the original response can still be logged to GDE.
 */
@Slf4j
public class ResponseBodyCapturingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {

        // Clear any previous data
        ResponseBodyHolder.clear();

        // Capture request headers before execution
        ResponseBodyHolder.setRequestHeaders(request.getHeaders());
        log.trace("Captured request headers for {}: {}", request.getURI(), request.getHeaders().keySet());

        ClientHttpResponse response = execution.execute(request, body);

        // Read and capture the response body
        byte[] responseBody;
        try {
            responseBody = StreamUtils.copyToByteArray(response.getBody());
            ResponseBodyHolder.setResponseBody(responseBody);
            log.trace("Captured response body: {} bytes for {}", responseBody.length, request.getURI());
        } catch (IOException e) {
            log.warn("Failed to capture response body for {}: {}", request.getURI(), e.getMessage());
            // Return original response if we can't read the body
            return response;
        }

        // Return a wrapper that allows the body to be read again
        return new BufferedClientHttpResponse(response, responseBody);
    }

    /**
     * Wrapper for ClientHttpResponse that provides the buffered body.
     * This allows the response body to be read multiple times.
     */
    private static class BufferedClientHttpResponse implements ClientHttpResponse {

        private final ClientHttpResponse original;
        private final byte[] body;

        BufferedClientHttpResponse(ClientHttpResponse original, byte[] body) {
            this.original = original;
            this.body = body;
        }

        @Override
        public InputStream getBody() throws IOException {
            // Return a new ByteArrayInputStream each time, allowing multiple reads
            return new ByteArrayInputStream(body);
        }

        @Override
        public HttpHeaders getHeaders() {
            return original.getHeaders();
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return original.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return original.getStatusText();
        }

        @Override
        public void close() {
            original.close();
        }
    }
}
