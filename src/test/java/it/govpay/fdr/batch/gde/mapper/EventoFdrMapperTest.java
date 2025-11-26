package it.govpay.fdr.batch.gde.mapper;

import it.govpay.fdr.batch.entity.Fr;
import it.govpay.gde.client.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for EventoFdrMapper.
 */
class EventoFdrMapperTest {

    private EventoFdrMapper mapper;
    private Fr testFr;

    @BeforeEach
    void setUp() {
        mapper = new EventoFdrMapper();
        ReflectionTestUtils.setField(mapper, "clusterId", "TEST-CLUSTER");

        testFr = Fr.builder()
            .id(1L)
            .codFlusso("FDR-TEST-001")
            .codPsp("PSP001")
            .codDominio("12345678901")
            .revisione(1L)
            .build();
    }

    @Test
    void testCreateEvento() {
        // Given
        String tipoEvento = "TEST_EVENT";
        String transactionId = "tx-123";
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(5);

        // When
        NuovoEvento evento = mapper.createEvento(testFr, tipoEvento, transactionId, start, end);

        // Then
        assertThat(evento).isNotNull();
        assertThat(evento.getIdDominio()).isEqualTo("12345678901");
        assertThat(evento.getTipoEvento()).isEqualTo("TEST_EVENT");
        assertThat(evento.getTransactionId()).isEqualTo("tx-123");
        assertThat(evento.getClusterId()).isEqualTo("TEST-CLUSTER");
        assertThat(evento.getCategoriaEvento()).isEqualTo(CategoriaEvento.INTERFACCIA);
        assertThat(evento.getRuolo()).isEqualTo(RuoloEvento.CLIENT);
        assertThat(evento.getComponente()).isEqualTo(ComponenteEvento.API_PAGOPA);
        assertThat(evento.getDataEvento()).isEqualTo(start);
        assertThat(evento.getDurataEvento()).isEqualTo(5L);

        // Check DatiPagoPA
        assertThat(evento.getDatiPagoPA()).isNotNull();
        assertThat(evento.getDatiPagoPA().getIdPsp()).isEqualTo("PSP001");
        assertThat(evento.getDatiPagoPA().getIdDominio()).isEqualTo("12345678901");
        assertThat(evento.getDatiPagoPA().getIdFlusso()).isEqualTo("FDR-TEST-001");
    }

    @Test
    void testCreateEventoWithNullFr() {
        // Given
        String tipoEvento = "TEST_EVENT";
        String transactionId = "tx-123";
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(3);

        // When
        NuovoEvento evento = mapper.createEvento(null, tipoEvento, transactionId, start, end);

        // Then
        assertThat(evento).isNotNull();
        assertThat(evento.getIdDominio()).isNull();
        assertThat(evento.getDatiPagoPA()).isNull();
        assertThat(evento.getTipoEvento()).isEqualTo("TEST_EVENT");
        assertThat(evento.getClusterId()).isEqualTo("TEST-CLUSTER");
    }

    @Test
    void testCreateEventoOk() {
        // Given
        String tipoEvento = "GET_FLOW";
        String transactionId = "tx-ok-123";
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(2);

        // When
        NuovoEvento evento = mapper.createEventoOk(testFr, tipoEvento, transactionId, start, end);

        // Then
        assertThat(evento).isNotNull();
        assertThat(evento.getEsito()).isEqualTo(EsitoEvento.OK);
        assertThat(evento.getTipoEvento()).isEqualTo("GET_FLOW");
    }

    @Test
    void testCreateEventoKoWithHttpClientError() {
        // Given
        String tipoEvento = "GET_FLOW";
        String transactionId = "tx-ko-123";
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(2);
        HttpClientErrorException exception = new HttpClientErrorException(
            HttpStatus.NOT_FOUND, "Not Found", "Resource not found".getBytes(), null);

        // When
        NuovoEvento evento = mapper.createEventoKo(testFr, tipoEvento, transactionId,
            start, end, null, exception);

        // Then
        assertThat(evento).isNotNull();
        assertThat(evento.getEsito()).isEqualTo(EsitoEvento.KO);
        assertThat(evento.getSottotipoEsito()).isEqualTo("404");
        assertThat(evento.getDettaglioEsito()).contains("Resource not found");
    }

    @Test
    void testCreateEventoKoWithHttpServerError() {
        // Given
        String tipoEvento = "GET_FLOW";
        String transactionId = "tx-fail-123";
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(2);
        HttpServerErrorException exception = new HttpServerErrorException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Server Error");

        // When
        NuovoEvento evento = mapper.createEventoKo(testFr, tipoEvento, transactionId,
            start, end, null, exception);

        // Then
        assertThat(evento).isNotNull();
        assertThat(evento.getEsito()).isEqualTo(EsitoEvento.FAIL);
        assertThat(evento.getSottotipoEsito()).isEqualTo("500");
    }

    @Test
    void testCreateEventoKoWithGenericException() {
        // Given
        String tipoEvento = "GET_FLOW";
        String transactionId = "tx-fail-123";
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(2);
        RestClientException exception = new RestClientException("Connection timeout");

        // When
        NuovoEvento evento = mapper.createEventoKo(testFr, tipoEvento, transactionId,
            start, end, null, exception);

        // Then
        assertThat(evento).isNotNull();
        assertThat(evento.getEsito()).isEqualTo(EsitoEvento.FAIL);
        assertThat(evento.getDettaglioEsito()).isEqualTo("Connection timeout");
        assertThat(evento.getSottotipoEsito()).isEqualTo("500");
    }

    @Test
    void testSetParametriRichiesta() {
        // Given
        NuovoEvento evento = mapper.createEvento(testFr, "TEST", "tx-1",
            OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));
        String url = "http://api.example.com/test";
        String method = "GET";
        List<Header> headers = new ArrayList<>();
        Header header = new Header();
        header.setNome("Content-Type");
        header.setValore("application/json");
        headers.add(header);

        // When
        mapper.setParametriRichiesta(evento, url, method, headers);

        // Then
        assertThat(evento.getParametriRichiesta()).isNotNull();
        assertThat(evento.getParametriRichiesta().getUrl()).isEqualTo(url);
        assertThat(evento.getParametriRichiesta().getMethod()).isEqualTo(method);
        assertThat(evento.getParametriRichiesta().getHeaders()).hasSize(1);
        assertThat(evento.getParametriRichiesta().getHeaders().get(0).getNome())
            .isEqualTo("Content-Type");
    }

    @Test
    void testSetParametriRispostaWithResponseEntity() {
        // Given
        NuovoEvento evento = mapper.createEvento(testFr, "TEST", "tx-1",
            OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));
        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC);

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add("Content-Type", "application/json");
        ResponseEntity<String> response = ResponseEntity.ok()
            .headers(httpHeaders)
            .body("test response");

        // When
        mapper.setParametriRisposta(evento, end, response, null);

        // Then
        assertThat(evento.getParametriRisposta()).isNotNull();
        assertThat(evento.getParametriRisposta().getDataOraRisposta()).isEqualTo(end);
        assertThat(evento.getParametriRisposta().getStatus()).isEqualTo(BigDecimal.valueOf(200));
        assertThat(evento.getParametriRisposta().getHeaders()).isNotEmpty();
    }

    @Test
    void testSetParametriRispostaWithHttpException() {
        // Given
        NuovoEvento evento = mapper.createEvento(testFr, "TEST", "tx-1",
            OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));
        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC);

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add("Content-Type", "application/json");
        HttpClientErrorException exception = new HttpClientErrorException(
            HttpStatus.BAD_REQUEST, "Bad Request", httpHeaders,
            "Invalid request".getBytes(), null);

        // When
        mapper.setParametriRisposta(evento, end, null, exception);

        // Then
        assertThat(evento.getParametriRisposta()).isNotNull();
        assertThat(evento.getParametriRisposta().getStatus()).isEqualTo(BigDecimal.valueOf(400));
        assertThat(evento.getParametriRisposta().getHeaders()).isNotEmpty();
    }

    @Test
    void testSetParametriRispostaWithGenericException() {
        // Given
        NuovoEvento evento = mapper.createEvento(testFr, "TEST", "tx-1",
            OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));
        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC);
        RestClientException exception = new RestClientException("Network error");

        // When
        mapper.setParametriRisposta(evento, end, null, exception);

        // Then
        assertThat(evento.getParametriRisposta()).isNotNull();
        assertThat(evento.getParametriRisposta().getStatus()).isEqualTo(BigDecimal.valueOf(500));
    }

    @Test
    void testSetParametriRispostaWithNoResponseAndNoException() {
        // Given
        NuovoEvento evento = mapper.createEvento(testFr, "TEST", "tx-1",
            OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));
        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC);

        // When
        mapper.setParametriRisposta(evento, end, null, null);

        // Then
        assertThat(evento.getParametriRisposta()).isNotNull();
        assertThat(evento.getParametriRisposta().getStatus()).isEqualTo(BigDecimal.valueOf(500));
    }
}
