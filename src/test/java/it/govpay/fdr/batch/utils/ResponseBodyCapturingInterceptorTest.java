package it.govpay.fdr.batch.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Unit tests for ResponseBodyCapturingInterceptor.
 */
@DisplayName("ResponseBodyCapturingInterceptor Tests")
class ResponseBodyCapturingInterceptorTest {

    private ResponseBodyCapturingInterceptor interceptor;
    private HttpRequest mockRequest;
    private ClientHttpRequestExecution mockExecution;
    private ClientHttpResponse mockResponse;
    private HttpHeaders requestHeaders;
    private HttpHeaders responseHeaders;

    @BeforeEach
    void setUp() {
        interceptor = new ResponseBodyCapturingInterceptor();
        mockRequest = mock(HttpRequest.class);
        mockExecution = mock(ClientHttpRequestExecution.class);
        mockResponse = mock(ClientHttpResponse.class);

        requestHeaders = new HttpHeaders();
        requestHeaders.add("Ocp-Apim-Subscription-Key", "test-key");
        requestHeaders.add("Accept", "application/json");

        responseHeaders = new HttpHeaders();
        responseHeaders.add("Content-Type", "application/json");

        when(mockRequest.getHeaders()).thenReturn(requestHeaders);
        when(mockRequest.getURI()).thenReturn(URI.create("https://api.example.com/test"));
        when(mockRequest.getMethod()).thenReturn(HttpMethod.GET);

        // Clear any previous data
        ResponseBodyHolder.clear();
    }

    @Test
    @DisplayName("Should capture request headers and response body successfully")
    void testInterceptCapturesHeadersAndBody() throws IOException {
        // Given
        String responseBody = "{\"status\":\"success\",\"data\":[1,2,3]}";
        byte[] responseBodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);

        when(mockExecution.execute(any(), any())).thenReturn(mockResponse);
        when(mockResponse.getBody()).thenReturn(new ByteArrayInputStream(responseBodyBytes));
        when(mockResponse.getHeaders()).thenReturn(responseHeaders);
        when(mockResponse.getStatusCode()).thenReturn(HttpStatus.OK);
        when(mockResponse.getStatusText()).thenReturn("OK");

        // When
        ClientHttpResponse result = interceptor.intercept(mockRequest, new byte[0], mockExecution);

        // Then
        // Verify request headers were captured
        HttpHeaders capturedHeaders = ResponseBodyHolder.getRequestHeaders();
        assertThat(capturedHeaders).isNotNull();
        assertThat(capturedHeaders.getFirst("Ocp-Apim-Subscription-Key")).isEqualTo("test-key");
        assertThat(capturedHeaders.getFirst("Accept")).isEqualTo("application/json");

        // Verify response body was captured
        byte[] capturedBody = ResponseBodyHolder.getResponseBody();
        assertThat(capturedBody).isNotNull();
        assertThat(new String(capturedBody, StandardCharsets.UTF_8)).isEqualTo(responseBody);

        // Verify response wrapper works correctly
        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getStatusText()).isEqualTo("OK");
        assertThat(result.getHeaders()).isEqualTo(responseHeaders);

        // Verify body can be read multiple times
        String firstRead = new String(result.getBody().readAllBytes(), StandardCharsets.UTF_8);
        String secondRead = new String(result.getBody().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(firstRead).isEqualTo(responseBody);
        assertThat(secondRead).isEqualTo(responseBody);
    }

    @Test
    @DisplayName("Should clear previous data before capturing new data")
    void testInterceptClearsPreviousData() throws IOException {
        // Given - set previous data
        ResponseBodyHolder.setRequestHeaders(new HttpHeaders());
        ResponseBodyHolder.setResponseBody("old data".getBytes());

        String responseBody = "{\"new\":\"data\"}";
        when(mockExecution.execute(any(), any())).thenReturn(mockResponse);
        when(mockResponse.getBody()).thenReturn(new ByteArrayInputStream(responseBody.getBytes()));
        when(mockResponse.getHeaders()).thenReturn(responseHeaders);
        when(mockResponse.getStatusCode()).thenReturn(HttpStatus.OK);

        // When
        interceptor.intercept(mockRequest, new byte[0], mockExecution);

        // Then - verify new data replaced old
        assertThat(ResponseBodyHolder.getRequestHeaders()).isEqualTo(requestHeaders);
        assertThat(new String(ResponseBodyHolder.getResponseBody())).isEqualTo(responseBody);
    }

    @Test
    @DisplayName("Should return original response when body read fails")
    void testInterceptReturnsOriginalOnBodyReadFailure() throws IOException {
        // Given
        when(mockExecution.execute(any(), any())).thenReturn(mockResponse);
        when(mockResponse.getBody()).thenThrow(new IOException("Stream closed"));
        when(mockResponse.getHeaders()).thenReturn(responseHeaders);

        // When
        ClientHttpResponse result = interceptor.intercept(mockRequest, new byte[0], mockExecution);

        // Then - should return original response
        assertThat(result).isEqualTo(mockResponse);

        // Headers should still be captured
        assertThat(ResponseBodyHolder.getRequestHeaders()).isEqualTo(requestHeaders);

        // Body should not be captured
        assertThat(ResponseBodyHolder.getResponseBody()).isNull();
    }

    @Test
    @DisplayName("Should handle empty response body")
    void testInterceptHandlesEmptyBody() throws IOException {
        // Given
        when(mockExecution.execute(any(), any())).thenReturn(mockResponse);
        when(mockResponse.getBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(mockResponse.getHeaders()).thenReturn(responseHeaders);
        when(mockResponse.getStatusCode()).thenReturn(HttpStatus.NO_CONTENT);

        // When
        ClientHttpResponse result = interceptor.intercept(mockRequest, new byte[0], mockExecution);

        // Then
        assertThat(ResponseBodyHolder.getResponseBody()).isNotNull();
        assertThat(ResponseBodyHolder.getResponseBody()).isEmpty();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("Should handle large response body")
    void testInterceptHandlesLargeBody() throws IOException {
        // Given - create a large response body (100KB)
        StringBuilder largeBody = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeBody.append("{\"index\":").append(i).append("},");
        }
        String responseBody = "[" + largeBody.substring(0, largeBody.length() - 1) + "]";

        when(mockExecution.execute(any(), any())).thenReturn(mockResponse);
        when(mockResponse.getBody()).thenReturn(new ByteArrayInputStream(responseBody.getBytes()));
        when(mockResponse.getHeaders()).thenReturn(responseHeaders);
        when(mockResponse.getStatusCode()).thenReturn(HttpStatus.OK);

        // When
        ClientHttpResponse result = interceptor.intercept(mockRequest, new byte[0], mockExecution);

        // Then
        byte[] capturedBody = ResponseBodyHolder.getResponseBody();
        assertThat(capturedBody).isNotNull();
        assertThat(capturedBody.length).isEqualTo(responseBody.getBytes().length);

        // Body should still be readable from response
        String readBody = new String(result.getBody().readAllBytes());
        assertThat(readBody).isEqualTo(responseBody);
    }

    @Test
    @DisplayName("Should execute request with provided body")
    void testInterceptExecutesWithBody() throws IOException {
        // Given
        byte[] requestBody = "{\"request\":\"data\"}".getBytes();
        String responseBody = "{\"response\":\"data\"}";

        when(mockExecution.execute(mockRequest, requestBody)).thenReturn(mockResponse);
        when(mockResponse.getBody()).thenReturn(new ByteArrayInputStream(responseBody.getBytes()));
        when(mockResponse.getHeaders()).thenReturn(responseHeaders);
        when(mockResponse.getStatusCode()).thenReturn(HttpStatus.OK);

        // When
        interceptor.intercept(mockRequest, requestBody, mockExecution);

        // Then
        verify(mockExecution).execute(mockRequest, requestBody);
    }

    @Test
    @DisplayName("BufferedClientHttpResponse close should delegate to original")
    void testBufferedResponseClosesDelegates() throws IOException {
        // Given
        String responseBody = "{\"data\":\"test\"}";
        when(mockExecution.execute(any(), any())).thenReturn(mockResponse);
        when(mockResponse.getBody()).thenReturn(new ByteArrayInputStream(responseBody.getBytes()));
        when(mockResponse.getHeaders()).thenReturn(responseHeaders);
        when(mockResponse.getStatusCode()).thenReturn(HttpStatus.OK);

        // When
        ClientHttpResponse result = interceptor.intercept(mockRequest, new byte[0], mockExecution);
        result.close();

        // Then
        verify(mockResponse).close();
    }

    @Test
    @DisplayName("Should capture headers with multiple values")
    void testInterceptCapturesMultiValueHeaders() throws IOException {
        // Given
        HttpHeaders multiValueHeaders = new HttpHeaders();
        multiValueHeaders.add("Accept", "application/json");
        multiValueHeaders.add("Accept", "text/plain");
        multiValueHeaders.add("X-Custom-Header", "value1");

        when(mockRequest.getHeaders()).thenReturn(multiValueHeaders);
        when(mockExecution.execute(any(), any())).thenReturn(mockResponse);
        when(mockResponse.getBody()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
        when(mockResponse.getHeaders()).thenReturn(responseHeaders);
        when(mockResponse.getStatusCode()).thenReturn(HttpStatus.OK);

        // When
        interceptor.intercept(mockRequest, new byte[0], mockExecution);

        // Then
        HttpHeaders captured = ResponseBodyHolder.getRequestHeaders();
        assertThat(captured.get("Accept")).containsExactly("application/json", "text/plain");
        assertThat(captured.getFirst("X-Custom-Header")).isEqualTo("value1");
    }
}
