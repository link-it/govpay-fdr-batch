package it.govpay.fdr.batch.utils;

import org.springframework.http.HttpHeaders;

/**
 * Thread-local holder for storing HTTP request/response data.
 * Used to capture request headers and response payload for GDE logging.
 *
 * Usage pattern:
 * 1. Interceptor captures request headers via setRequestHeaders()
 * 2. Interceptor captures raw response body via setResponseBody()
 * 3. GdeService retrieves the data for event logging
 * 4. Finally, clear() is called to prevent memory leaks
 */
public final class ResponseBodyHolder {

    private static final ThreadLocal<byte[]> RESPONSE_BODY = new ThreadLocal<>();
    private static final ThreadLocal<HttpHeaders> REQUEST_HEADERS = new ThreadLocal<>();

    private ResponseBodyHolder() {
        // Utility class - prevent instantiation
    }

    /**
     * Stores the raw response body for the current thread.
     *
     * @param body the raw response body bytes
     */
    public static void setResponseBody(byte[] body) {
        RESPONSE_BODY.set(body);
    }

    /**
     * Retrieves the stored response body for the current thread.
     *
     * @return the raw response body bytes, or null if not set
     */
    public static byte[] getResponseBody() {
        return RESPONSE_BODY.get();
    }

    /**
     * Stores the request headers for the current thread.
     *
     * @param headers the HTTP request headers
     */
    public static void setRequestHeaders(HttpHeaders headers) {
        REQUEST_HEADERS.set(headers);
    }

    /**
     * Retrieves the stored request headers for the current thread.
     *
     * @return the HTTP request headers, or null if not set
     */
    public static HttpHeaders getRequestHeaders() {
        return REQUEST_HEADERS.get();
    }

    /**
     * Clears all stored data for the current thread.
     * Should always be called in a finally block to prevent memory leaks.
     */
    public static void clear() {
        RESPONSE_BODY.remove();
        REQUEST_HEADERS.remove();
    }
}
