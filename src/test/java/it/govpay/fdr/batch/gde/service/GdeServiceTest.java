package it.govpay.fdr.batch.gde.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.config.PagoPAProperties;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.gde.mapper.EventoFdrMapper;
import it.govpay.gde.client.ApiException;
import it.govpay.gde.client.api.EventiApi;
import it.govpay.gde.client.model.EsitoEvento;
import it.govpay.gde.client.model.NuovoEvento;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test class for GdeService.
 */
@ExtendWith(MockitoExtension.class)
class GdeServiceTest {

    @Mock
    private EventiApi eventiApi;

    @Mock
    private EventoFdrMapper eventoFdrMapper;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private PagoPAProperties pagoPAProperties;

    @Captor
    private ArgumentCaptor<NuovoEvento> eventoCaptor;

    private GdeService gdeService;
    private Fr testFr;

    @BeforeEach
    void setUp() {
        lenient().when(pagoPAProperties.getBaseUrl()).thenReturn("https://api.pagopa.it");
        gdeService = new GdeService(eventiApi, eventoFdrMapper, objectMapper, pagoPAProperties);
        ReflectionTestUtils.setField(gdeService, "gdeEnabled", true);

        testFr = Fr.builder()
            .id(1L)
            .codFlusso("FDR-TEST-001")
            .codPsp("PSP001")
            .codDominio("12345678901")
            .revisione(1L)
            .build();
    }

    @Test
    void testInviaEventoWhenEnabled() throws Exception {
        // Given
        NuovoEvento evento = new NuovoEvento();
        evento.setTipoEvento("TEST_EVENT");
        doNothing().when(eventiApi).addEvento(any(NuovoEvento.class));

        // When
        gdeService.inviaEvento(evento);

        // Then - wait for async execution
        await().untilAsserted(() ->
            verify(eventiApi, times(1)).addEvento(evento)
        );
    }

    @Test
    void testInviaEventoWhenDisabled() {
        // Given
        ReflectionTestUtils.setField(gdeService, "gdeEnabled", false);
        NuovoEvento evento = new NuovoEvento();
        evento.setTipoEvento("TEST_EVENT");

        // When
        gdeService.inviaEvento(evento);

        // Then - should not call API
        await().untilAsserted(() ->
        verify(eventiApi, never()).addEvento(any())
        );
        
    }

    @Test
    void testInviaEventoHandlesException() throws Exception {
        // Given
        NuovoEvento evento = new NuovoEvento();
        evento.setTipoEvento("TEST_EVENT");
        doThrow(new ApiException("API Error")).when(eventiApi).addEvento(any());

        // When
        gdeService.inviaEvento(evento);

        // Then - should not throw exception (error is logged)
        await().untilAsserted(() ->
            verify(eventiApi, times(1)).addEvento(evento)
        );
        // No exception should be thrown to the caller
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
        gdeService.saveGetPublishedFlowsOk(organizationId, pspId, flowDate, start, end, flowsCount, null);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoOk(isNull(), eq(Costanti.OPERATION_GET_ALL_PUBLISHED_FLOWS),
                anyString(), eq(start), eq(end));
            verify(eventoFdrMapper).setParametriRichiesta(eq(mockEvento),
                eq("https://api.pagopa.it/organizations/ORG001/fdrs"), eq("GET"), anyList());
        });

        // Verify idDominio is always set (it's always known)
        assertThat(mockEvento.getIdDominio()).isEqualTo(organizationId);

        // Verify datiPagoPA is always set with idDominio and idPsp
        assertThat(mockEvento.getDatiPagoPA()).isNotNull();
        assertThat(mockEvento.getDatiPagoPA().getIdDominio()).isEqualTo(organizationId);
        assertThat(mockEvento.getDatiPagoPA().getIdPsp()).isEqualTo(pspId);
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
        gdeService.saveGetPublishedFlowsKo(organizationId, pspId, flowDate, start, end, null, exception);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoKo(isNull(), eq(Costanti.OPERATION_GET_ALL_PUBLISHED_FLOWS),
                anyString(), eq(start), eq(end), isNull(), eq(exception));
        });

        // Verify datiPagoPA is always set with idDominio and idPsp
        assertThat(mockEvento.getDatiPagoPA()).isNotNull();
        assertThat(mockEvento.getDatiPagoPA().getIdDominio()).isEqualTo(organizationId);
        assertThat(mockEvento.getDatiPagoPA().getIdPsp()).isNull(); // pspId was null in this test
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
        gdeService.saveGetFlowDetailsOk(testFr, start, end, paymentsCount, null);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoOk(eq(testFr), eq(Costanti.OPERATION_GET_SINGLE_PUBLISHED_FLOW),
                anyString(), eq(start), eq(end));
            verify(eventoFdrMapper).setParametriRichiesta(eq(mockEvento),
                eq("https://api.pagopa.it/organizations/12345678901/fdrs/FDR-TEST-001/revisions/1/psps/PSP001"),
                eq("GET"), anyList());
        });
        assertThat(mockEvento.getSottotipoEvento()).contains("fdr=FDR-TEST-001");
        assertThat(mockEvento.getDettaglioEsito()).contains("10 payments");
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
        gdeService.saveGetFlowDetailsKo(testFr, start, end, null, exception);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoKo(eq(testFr), eq(Costanti.OPERATION_GET_SINGLE_PUBLISHED_FLOW),
                anyString(), eq(start), eq(end), isNull(), eq(exception));
        });
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
        gdeService.saveGetPaymentsOk(testFr, start, end, paymentsCount, null);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoOk(eq(testFr), eq(Costanti.OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW),
                anyString(), eq(start), eq(end));
            verify(eventoFdrMapper).setParametriRichiesta(eq(mockEvento),
                eq("https://api.pagopa.it/organizations/12345678901/fdrs/FDR-TEST-001/revisions/1/psps/PSP001/payments"),
                eq("GET"), anyList());
        });
        assertThat(mockEvento.getDettaglioEsito()).contains("25 payments");
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
        gdeService.saveGetPaymentsKo(testFr, start, end, null, exception);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoKo(eq(testFr), eq(Costanti.OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW),
                anyString(), eq(start), eq(end), isNull(), eq(exception));
            verify(eventoFdrMapper).setParametriRichiesta(eq(mockEvento),
                eq("https://api.pagopa.it/organizations/12345678901/fdrs/FDR-TEST-001/revisions/1/psps/PSP001/payments"),
                eq("GET"), anyList());
        });
    }

    @Test
    void testInviaEventoWhenEventiApiIsNull() throws Exception {
        // Given - GdeService with null EventiApi
        GdeService gdeServiceNullApi = new GdeService(null, eventoFdrMapper, objectMapper, pagoPAProperties);
        ReflectionTestUtils.setField(gdeServiceNullApi, "gdeEnabled", true);

        NuovoEvento evento = new NuovoEvento();
        evento.setTipoEvento("TEST_EVENT");

        // When
        gdeServiceNullApi.inviaEvento(evento);

        // Then - should not throw exception, API should never be called
        // Give time for async execution that should not happen
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        verify(eventiApi, never()).addEvento(any());
    }

}
