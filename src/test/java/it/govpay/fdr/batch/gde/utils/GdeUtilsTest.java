package it.govpay.fdr.batch.gde.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.govpay.fdr.batch.Costanti;
import it.govpay.gde.client.model.DettaglioRisposta;
import it.govpay.gde.client.model.NuovoEvento;

/**
 * Test class for GdeUtils utility methods
 */
@DisplayName("GdeUtils Tests")
class GdeUtilsTest {

    private ObjectMapper objectMapper;
    private NuovoEvento nuovoEvento;
    private DettaglioRisposta dettaglioRisposta;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        nuovoEvento = new NuovoEvento();
        dettaglioRisposta = new DettaglioRisposta();
        nuovoEvento.setParametriRisposta(dettaglioRisposta);
    }

    // ========== Tests for serializzaPayload with HttpStatusCodeException ==========

    @Test
    @DisplayName("serializzaPayload with HttpClientErrorException should encode response body")
    void testSerializzaPayloadWithHttpClientErrorException() {
        // Given
        String responseBody = "{\"error\":\"Not Found\"}";
        HttpClientErrorException exception = new HttpClientErrorException(
            HttpStatus.NOT_FOUND,
            "Not Found",
            responseBody.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8
        );

        // When
        GdeUtils.serializzaPayload(objectMapper, nuovoEvento, null, exception);

        // Then
        String expectedPayload = Base64.getEncoder().encodeToString(responseBody.getBytes());
        assertThat(dettaglioRisposta.getPayload()).isEqualTo(expectedPayload);
    }

    @Test
    @DisplayName("serializzaPayload with HttpServerErrorException should encode response body")
    void testSerializzaPayloadWithHttpServerErrorException() {
        // Given
        String responseBody = "{\"error\":\"Internal Server Error\"}";
        HttpServerErrorException exception = new HttpServerErrorException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal Server Error",
            responseBody.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8
        );

        // When
        GdeUtils.serializzaPayload(objectMapper, nuovoEvento, null, exception);

        // Then
        String expectedPayload = Base64.getEncoder().encodeToString(responseBody.getBytes());
        assertThat(dettaglioRisposta.getPayload()).isEqualTo(expectedPayload);
    }

    @Test
    @DisplayName("serializzaPayload with HttpStatusCodeException with empty body should encode empty string")
    void testSerializzaPayloadWithHttpStatusCodeExceptionEmptyBody() {
        // Given
        HttpClientErrorException exception = new HttpClientErrorException(
            HttpStatus.NOT_FOUND,
            "Not Found",
            new byte[0],
            StandardCharsets.UTF_8
        );

        // When
        GdeUtils.serializzaPayload(objectMapper, nuovoEvento, null, exception);

        // Then
        String expectedPayload = Base64.getEncoder().encodeToString(new byte[0]);
        assertThat(dettaglioRisposta.getPayload()).isEqualTo(expectedPayload);
    }

    // ========== Tests for serializzaPayload with RestClientException ==========

    @Test
    @DisplayName("serializzaPayload with generic RestClientException should encode message")
    void testSerializzaPayloadWithRestClientException() {
        // Given
        String errorMessage = "Connection timeout";
        RestClientException exception = new RestClientException(errorMessage);

        // When
        GdeUtils.serializzaPayload(objectMapper, nuovoEvento, null, exception);

        // Then
        String expectedPayload = Base64.getEncoder().encodeToString(errorMessage.getBytes());
        assertThat(dettaglioRisposta.getPayload()).isEqualTo(expectedPayload);
    }

    @Test
    @DisplayName("serializzaPayload with RestClientException with empty message should encode empty message")
    void testSerializzaPayloadWithRestClientExceptionEmptyMessage() {
        // Given
        String errorMessage = "";
        RestClientException exception = new RestClientException(errorMessage);

        // When
        GdeUtils.serializzaPayload(objectMapper, nuovoEvento, null, exception);

        // Then
        String expectedPayload = Base64.getEncoder().encodeToString(errorMessage.getBytes());
        assertThat(dettaglioRisposta.getPayload()).isEqualTo(expectedPayload);
    }

    // ========== Tests for serializzaPayload with ResponseEntity ==========

    @Test
    @DisplayName("serializzaPayload with ResponseEntity should serialize and encode body")
    void testSerializzaPayloadWithResponseEntity() {
        // Given
        TestResponse responseBody = new TestResponse("success", 200);
        ResponseEntity<TestResponse> response = ResponseEntity.ok(responseBody);

        // When
        GdeUtils.serializzaPayload(objectMapper, nuovoEvento, response, null);

        // Then
        assertThat(dettaglioRisposta.getPayload()).isNotNull();

        // Decode and verify
        String decodedPayload = new String(Base64.getDecoder().decode(dettaglioRisposta.getPayload()));
        assertThat(decodedPayload).contains("\"status\":\"success\"");
        assertThat(decodedPayload).contains("\"code\":200");
    }

    @Test
    @DisplayName("serializzaPayload with ResponseEntity with null body should encode 'null'")
    void testSerializzaPayloadWithResponseEntityNullBody() {
        // Given
        ResponseEntity<Object> response = ResponseEntity.ok().build();

        // When
        GdeUtils.serializzaPayload(objectMapper, nuovoEvento, response, null);

        // Then
        String expectedPayload = Base64.getEncoder().encodeToString("null".getBytes());
        assertThat(dettaglioRisposta.getPayload()).isEqualTo(expectedPayload);
    }

    @Test
    @DisplayName("serializzaPayload with ResponseEntity containing complex object")
    void testSerializzaPayloadWithComplexObject() {
        // Given
        ComplexTestObject complexObject = new ComplexTestObject();
        complexObject.setName("Test Object");
        complexObject.setCount(42);
        complexObject.setNested(new NestedObject("nested value"));

        ResponseEntity<ComplexTestObject> response = ResponseEntity.ok(complexObject);

        // When
        GdeUtils.serializzaPayload(objectMapper, nuovoEvento, response, null);

        // Then
        assertThat(dettaglioRisposta.getPayload()).isNotNull();

        // Decode and verify
        String decodedPayload = new String(Base64.getDecoder().decode(dettaglioRisposta.getPayload()));
        assertThat(decodedPayload).contains("\"name\":\"Test Object\"");
        assertThat(decodedPayload).contains("\"count\":42");
        assertThat(decodedPayload).contains("\"nested\"");
        assertThat(decodedPayload).contains("\"value\":\"nested value\"");
    }

    // ========== Tests for serializzaPayload edge cases ==========

    @Test
    @DisplayName("serializzaPayload with null parametriRisposta should not throw exception")
    void testSerializzaPayloadWithNullParametriRisposta() {
        // Given
        nuovoEvento.setParametriRisposta(null);
        ResponseEntity<String> response = ResponseEntity.ok("test");

        // When/Then - should not throw
        GdeUtils.serializzaPayload(objectMapper, nuovoEvento, response, null);
    }

    @Test
    @DisplayName("serializzaPayload with both response and exception null should do nothing")
    void testSerializzaPayloadWithBothNull() {
        // Given - dettaglioRisposta starts with null payload
        assertThat(dettaglioRisposta.getPayload()).isNull();

        // When
        GdeUtils.serializzaPayload(objectMapper, nuovoEvento, null, null);

        // Then - payload should still be null
        assertThat(dettaglioRisposta.getPayload()).isNull();
    }

    @Test
    @DisplayName("serializzaPayload prioritizes exception over response")
    void testSerializzaPayloadPrioritizesException() {
        // Given
        RestClientException exception = new RestClientException("Error occurred");
        ResponseEntity<String> response = ResponseEntity.ok("success");

        // When
        GdeUtils.serializzaPayload(objectMapper, nuovoEvento, response, exception);

        // Then - should use exception, not response
        String decodedPayload = new String(Base64.getDecoder().decode(dettaglioRisposta.getPayload()));
        assertThat(decodedPayload).isEqualTo("Error occurred");
        assertThat(decodedPayload).doesNotContain("success");
    }

    // ========== Tests for writeValueAsString ==========

    @Test
    @DisplayName("writeValueAsString with serializable object should return JSON string")
    void testWriteValueAsStringWithSerializableObject() {
        // Given
        TestResponse testObject = new TestResponse("test", 123);

        // When
        String result = GdeUtils.writeValueAsString(objectMapper, testObject);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).contains("\"status\":\"test\"");
        assertThat(result).contains("\"code\":123");
    }

    @Test
    @DisplayName("writeValueAsString with null should return 'null'")
    void testWriteValueAsStringWithNull() {
        // When
        String result = GdeUtils.writeValueAsString(objectMapper, null);

        // Then
        assertThat(result).isEqualTo("null");
    }

    @Test
    @DisplayName("writeValueAsString with simple string should return JSON string")
    void testWriteValueAsStringWithSimpleString() {
        // When
        String result = GdeUtils.writeValueAsString(objectMapper, "simple text");

        // Then
        assertThat(result).isEqualTo("\"simple text\"");
    }

    @Test
    @DisplayName("writeValueAsString with number should return number as string")
    void testWriteValueAsStringWithNumber() {
        // When
        String result = GdeUtils.writeValueAsString(objectMapper, 42);

        // Then
        assertThat(result).isEqualTo("42");
    }

    @Test
    @DisplayName("writeValueAsString with JsonProcessingException should return error message")
    void testWriteValueAsStringWithJsonProcessingException() throws JsonProcessingException {
        // Given - mock ObjectMapper to throw exception
        ObjectMapper mockMapper = mock(ObjectMapper.class);
        when(mockMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("Serialization error") {});

        // When
        String result = GdeUtils.writeValueAsString(mockMapper, new TestResponse("test", 1));

        // Then
        assertThat(result).isEqualTo(Costanti.MSG_PAYLOAD_NON_SERIALIZZABILE);
    }

    // ========== Helper classes for testing ==========

    private static class TestResponse {
        private String status;
        private int code;

        public TestResponse(String status, int code) {
            this.status = status;
            this.code = code;
        }

        public String getStatus() {
            return status;
        }

        public int getCode() {
            return code;
        }
    }

    private static class ComplexTestObject {
        private String name;
        private int count;
        private NestedObject nested;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public NestedObject getNested() {
            return nested;
        }

        public void setNested(NestedObject nested) {
            this.nested = nested;
        }
    }

    private static class NestedObject {
        private String value;

        public NestedObject(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
