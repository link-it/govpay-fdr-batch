package it.govpay.fdr.batch.gde.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import it.govpay.common.client.model.Connettore;
import it.govpay.common.configurazione.service.ConfigurazioneService;
import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.gde.mapper.EventoFdrMapper;
import it.govpay.gde.client.beans.EsitoEvento;
import it.govpay.gde.client.beans.NuovoEvento;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test class for GdeService.
 */
@ExtendWith(MockitoExtension.class)
class GdeServiceTest {

    @Mock
    private EventoFdrMapper eventoFdrMapper;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ConfigurazioneService configurazioneService;

    @Mock
    private RestTemplate gdeRestTemplate;

    @Captor
    private ArgumentCaptor<NuovoEvento> eventoCaptor;

    /** Synchronous executor for deterministic testing (no Awaitility needed) */
    private final Executor syncExecutor = Runnable::run;

    private GdeService gdeService;
    private Fr testFr;
    private static final String PAGOPA_BASE_URL = "https://api.pagopa.it";

    @BeforeEach
    void setUp() {
        Connettore gdeConnettore = new Connettore();
        gdeConnettore.setUrl("http://localhost:10002/api/v1");
        gdeConnettore.setAbilitato(true);
        lenient().when(configurazioneService.getServizioGDE()).thenReturn(gdeConnettore);
        lenient().when(configurazioneService.isServizioGDEAbilitato()).thenReturn(true);
        lenient().when(configurazioneService.getRestTemplateGDE()).thenReturn(gdeRestTemplate);

        gdeService = new GdeService(objectMapper, syncExecutor, configurazioneService, eventoFdrMapper);

        testFr = Fr.builder()
            .id(1L)
            .codFlusso("FDR-TEST-001")
            .codPsp("PSP001")
            .codDominio("12345678901")
            .revisione(1L)
            .build();
    }

    @Test
    void testSendEventAsync() {
        // Given
        NuovoEvento evento = new NuovoEvento();
        evento.setTipoEvento("TEST_EVENT");
        when(gdeRestTemplate.postForEntity(anyString(), any(), eq(Void.class)))
            .thenReturn(ResponseEntity.ok().build());

        // When
        gdeService.sendEventAsync(evento);

        // Then
        verify(gdeRestTemplate, times(1)).postForEntity(
            eq("http://localhost:10002/api/v1/eventi"), eq(evento), eq(Void.class));
    }

    @Test
    void testSendEventAsyncHandlesException() {
        // Given
        NuovoEvento evento = new NuovoEvento();
        evento.setTipoEvento("TEST_EVENT");
        doThrow(new RestClientException("Connection refused"))
            .when(gdeRestTemplate).postForEntity(anyString(), any(), eq(Void.class));

        // When
        gdeService.sendEventAsync(evento);

        // Then - should not throw exception (error is logged)
        verify(gdeRestTemplate, times(1)).postForEntity(
            eq("http://localhost:10002/api/v1/eventi"), eq(evento), eq(Void.class));
    }

    @Test
    void testGetGdeEndpoint() {
        Connettore gdeConnettore = new Connettore();
        gdeConnettore.setUrl("http://gde-host:8080/api/v1");
        gdeConnettore.setAbilitato(true);
        when(configurazioneService.getServizioGDE()).thenReturn(gdeConnettore);

        NuovoEvento evento = new NuovoEvento();
        evento.setTipoEvento("TEST");
        when(gdeRestTemplate.postForEntity(anyString(), any(), eq(Void.class)))
            .thenReturn(ResponseEntity.ok().build());

        gdeService.sendEventAsync(evento);

        verify(gdeRestTemplate).postForEntity(
            eq("http://gde-host:8080/api/v1/eventi"), eq(evento), eq(Void.class));
    }

    @Test
    void testSaveGetPublishedFlowsOk() {
        // Given
        String organizationId = "ORG001";
        String pspId = "PSP001";
        String flowDate = "2025-01-01";
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(5);
        int flowsCount = 42;

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(Costanti.OPERATION_GET_ALL_PUBLISHED_FLOWS);
        mockEvento.setEsito(EsitoEvento.OK);

        when(eventoFdrMapper.createEventoOk(any(), eq(Costanti.OPERATION_GET_ALL_PUBLISHED_FLOWS),
            anyString(), eq(start), eq(end))).thenReturn(mockEvento);
        doNothing().when(eventoFdrMapper).setParametriRichiesta(any(), anyString(), anyString(), anyList());
        doNothing().when(eventoFdrMapper).setParametriRisposta(any(), any(), any(), any());

        // When
        gdeService.saveGetPublishedFlowsOk(organizationId, pspId, flowDate, start, end, flowsCount, null, PAGOPA_BASE_URL);

        // Then
        verify(eventoFdrMapper).createEventoOk(isNull(), eq(Costanti.OPERATION_GET_ALL_PUBLISHED_FLOWS),
            anyString(), eq(start), eq(end));
        verify(eventoFdrMapper).setParametriRichiesta(eq(mockEvento),
            eq("https://api.pagopa.it/organizations/ORG001/fdrs?publishedGt=2025-01-01"), eq("GET"), anyList());

        // Verify idDominio is always set (it's always known)
        assertThat(mockEvento.getIdDominio()).isEqualTo(organizationId);

        // Verify datiPagoPA is always set with idDominio and idPsp
        assertThat(mockEvento.getDatiPagoPA()).isNotNull();
        assertThat(mockEvento.getDatiPagoPA().getIdDominio()).isEqualTo(organizationId);
        assertThat(mockEvento.getDatiPagoPA().getIdPsp()).isEqualTo(pspId);

        // Verify event was sent via RestTemplate
        verify(gdeRestTemplate).postForEntity(anyString(), eq(mockEvento), eq(Void.class));
    }

    @Test
    void testSaveGetPublishedFlowsKo() {
        // Given
        String organizationId = "ORG001";
        String pspId = null; // Test with null PSP
        String flowDate = "2025-01-01";
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(5);
        RestClientException exception = new RestClientException("Connection error");

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(Costanti.OPERATION_GET_ALL_PUBLISHED_FLOWS);
        mockEvento.setEsito(EsitoEvento.KO);

        when(eventoFdrMapper.createEventoKo(any(), eq(Costanti.OPERATION_GET_ALL_PUBLISHED_FLOWS),
            anyString(), eq(start), eq(end), isNull(), eq(exception))).thenReturn(mockEvento);

        // When
        gdeService.saveGetPublishedFlowsKo(organizationId, pspId, flowDate, start, end, null, exception, PAGOPA_BASE_URL);

        // Then
        verify(eventoFdrMapper).createEventoKo(isNull(), eq(Costanti.OPERATION_GET_ALL_PUBLISHED_FLOWS),
            anyString(), eq(start), eq(end), isNull(), eq(exception));

        // Verify datiPagoPA is always set with idDominio and idPsp
        assertThat(mockEvento.getDatiPagoPA()).isNotNull();
        assertThat(mockEvento.getDatiPagoPA().getIdDominio()).isEqualTo(organizationId);
        assertThat(mockEvento.getDatiPagoPA().getIdPsp()).isNull(); // pspId was null in this test

        // Verify event was sent via RestTemplate
        verify(gdeRestTemplate).postForEntity(anyString(), eq(mockEvento), eq(Void.class));
    }

    @Test
    void testSaveGetFlowDetailsOk() {
        // Given
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(3);
        int paymentsCount = 10;

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(Costanti.OPERATION_GET_SINGLE_PUBLISHED_FLOW);
        mockEvento.setEsito(EsitoEvento.OK);
        mockEvento.setSottotipoEvento("fdr=" + testFr.getCodFlusso());

        when(eventoFdrMapper.createEventoOk(eq(testFr), eq(Costanti.OPERATION_GET_SINGLE_PUBLISHED_FLOW),
            anyString(), eq(start), eq(end))).thenReturn(mockEvento);

        // When
        gdeService.saveGetFlowDetailsOk(testFr, start, end, paymentsCount, null, PAGOPA_BASE_URL);

        // Then
        verify(eventoFdrMapper).createEventoOk(eq(testFr), eq(Costanti.OPERATION_GET_SINGLE_PUBLISHED_FLOW),
            anyString(), eq(start), eq(end));
        verify(eventoFdrMapper).setParametriRichiesta(eq(mockEvento),
            eq("https://api.pagopa.it/organizations/12345678901/fdrs/FDR-TEST-001/revisions/1/psps/PSP001"),
            eq("GET"), anyList());
        assertThat(mockEvento.getSottotipoEvento()).contains("fdr=FDR-TEST-001");
        assertThat(mockEvento.getDettaglioEsito()).contains("10 payments");

        verify(gdeRestTemplate).postForEntity(anyString(), eq(mockEvento), eq(Void.class));
    }

    @Test
    void testSaveGetFlowDetailsKo() {
        // Given
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(3);
        RestClientException exception = new RestClientException("Flow not found");

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(Costanti.OPERATION_GET_SINGLE_PUBLISHED_FLOW);
        mockEvento.setEsito(EsitoEvento.KO);

        when(eventoFdrMapper.createEventoKo(eq(testFr), eq(Costanti.OPERATION_GET_SINGLE_PUBLISHED_FLOW),
            anyString(), eq(start), eq(end), isNull(), eq(exception))).thenReturn(mockEvento);

        // When
        gdeService.saveGetFlowDetailsKo(testFr, start, end, null, exception, PAGOPA_BASE_URL);

        // Then
        verify(eventoFdrMapper).createEventoKo(eq(testFr), eq(Costanti.OPERATION_GET_SINGLE_PUBLISHED_FLOW),
            anyString(), eq(start), eq(end), isNull(), eq(exception));
        verify(gdeRestTemplate).postForEntity(anyString(), eq(mockEvento), eq(Void.class));
    }

    @Test
    void testSaveGetPaymentsOk() {
        // Given
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(2);
        int paymentsCount = 25;

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(Costanti.OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW);
        mockEvento.setEsito(EsitoEvento.OK);

        when(eventoFdrMapper.createEventoOk(eq(testFr), eq(Costanti.OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW),
            anyString(), eq(start), eq(end))).thenReturn(mockEvento);
        doNothing().when(eventoFdrMapper).setParametriRichiesta(any(), anyString(), anyString(), anyList());
        doNothing().when(eventoFdrMapper).setParametriRisposta(any(), any(), any(), any());

        // When
        gdeService.saveGetPaymentsOk(testFr, start, end, paymentsCount, null, PAGOPA_BASE_URL);

        // Then
        verify(eventoFdrMapper).createEventoOk(eq(testFr), eq(Costanti.OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW),
            anyString(), eq(start), eq(end));
        verify(eventoFdrMapper).setParametriRichiesta(eq(mockEvento),
            eq("https://api.pagopa.it/organizations/12345678901/fdrs/FDR-TEST-001/revisions/1/psps/PSP001/payments"),
            eq("GET"), anyList());
        assertThat(mockEvento.getDettaglioEsito()).contains("25 payments");

        verify(gdeRestTemplate).postForEntity(anyString(), eq(mockEvento), eq(Void.class));
    }

    @Test
    void testSaveGetPaymentsKo() {
        // Given
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(2);
        RestClientException exception = new RestClientException("Payments not found");

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(Costanti.OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW);
        mockEvento.setEsito(EsitoEvento.KO);

        when(eventoFdrMapper.createEventoKo(eq(testFr), eq(Costanti.OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW),
            anyString(), eq(start), eq(end), isNull(), eq(exception))).thenReturn(mockEvento);
        doNothing().when(eventoFdrMapper).setParametriRichiesta(any(), anyString(), anyString(), anyList());
        doNothing().when(eventoFdrMapper).setParametriRisposta(any(), any(), any(), any());

        // When
        gdeService.saveGetPaymentsKo(testFr, start, end, null, exception, PAGOPA_BASE_URL);

        // Then
        verify(eventoFdrMapper).createEventoKo(eq(testFr), eq(Costanti.OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW),
            anyString(), eq(start), eq(end), isNull(), eq(exception));
        verify(eventoFdrMapper).setParametriRichiesta(eq(mockEvento),
            eq("https://api.pagopa.it/organizations/12345678901/fdrs/FDR-TEST-001/revisions/1/psps/PSP001/payments"),
            eq("GET"), anyList());

        verify(gdeRestTemplate).postForEntity(anyString(), eq(mockEvento), eq(Void.class));
    }

    @Test
    void testSendEventAsyncSkipsWhenConnettoreDisabilitato() {
        // Given: GDE connector disabled
        when(configurazioneService.isServizioGDEAbilitato()).thenReturn(false);

        NuovoEvento evento = new NuovoEvento();
        evento.setTipoEvento("TEST_EVENT");

        // When
        gdeService.sendEventAsync(evento);

        // Then: RestTemplate should NOT be called
        verify(gdeRestTemplate, never()).postForEntity(anyString(), any(), any());
    }
}
