package it.govpay.fdr.batch.gde.service;

import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.gde.mapper.EventoFdrMapper;
import it.govpay.gde.client.ApiException;
import it.govpay.gde.client.api.EventiApi;
import it.govpay.gde.client.model.EsitoEvento;
import it.govpay.gde.client.model.NuovoEvento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test class for GdeService.
 */
@ExtendWith(MockitoExtension.class)
class GdeServiceTest {

    @Mock
    private EventiApi eventiApi;

    @Mock
    private EventoFdrMapper eventoFdrMapper;

    @Captor
    private ArgumentCaptor<NuovoEvento> eventoCaptor;

    private GdeService gdeService;
    private Fr testFr;

    @BeforeEach
    void setUp() {
        gdeService = new GdeService(eventiApi, eventoFdrMapper);
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
    void testInviaEventoWhenDisabled() throws Exception {
        // Given
        ReflectionTestUtils.setField(gdeService, "gdeEnabled", false);
        NuovoEvento evento = new NuovoEvento();
        evento.setTipoEvento("TEST_EVENT");

        // When
        gdeService.inviaEvento(evento);

        // Then - should not call API
        Thread.sleep(100); // Give time for async if it was executed
        verify(eventiApi, never()).addEvento(any());
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
    void testSaveGetPublishedFlowsOk() throws Exception {
        // Given
        String organizationId = "ORG001";
        String pspId = "PSP001";
        String flowDate = "2025-01-01";
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(5);
        String url = "http://api.example.com/flows";
        int flowsCount = 42;

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(GdeService.EventTypes.GET_PUBLISHED_FLOWS);
        mockEvento.setEsito(EsitoEvento.OK);

        when(eventoFdrMapper.createEventoOk(any(), eq(GdeService.EventTypes.GET_PUBLISHED_FLOWS),
            anyString(), eq(start), eq(end))).thenReturn(mockEvento);
        doNothing().when(eventoFdrMapper).setParametriRichiesta(any(), anyString(), anyString(), anyList());
        doNothing().when(eventoFdrMapper).setParametriRisposta(any(), any(), any(), any());

        // When
        gdeService.saveGetPublishedFlowsOk(organizationId, pspId, flowDate, start, end, url, flowsCount);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoOk(isNull(), eq(GdeService.EventTypes.GET_PUBLISHED_FLOWS),
                anyString(), eq(start), eq(end));
            verify(eventoFdrMapper).setParametriRichiesta(eq(mockEvento), eq(url), eq("GET"), anyList());
        });
    }

    @Test
    void testSaveGetPublishedFlowsKo() throws Exception {
        // Given
        String organizationId = "ORG001";
        String pspId = null; // Test with null PSP
        String flowDate = "2025-01-01";
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(5);
        String url = "http://api.example.com/flows";
        RestClientException exception = new RestClientException("Connection error");

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(GdeService.EventTypes.GET_PUBLISHED_FLOWS);
        mockEvento.setEsito(EsitoEvento.KO);

        when(eventoFdrMapper.createEventoKo(any(), eq(GdeService.EventTypes.GET_PUBLISHED_FLOWS),
            anyString(), eq(start), eq(end), isNull(), eq(exception))).thenReturn(mockEvento);

        // When
        gdeService.saveGetPublishedFlowsKo(organizationId, pspId, flowDate, start, end, url, exception);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoKo(isNull(), eq(GdeService.EventTypes.GET_PUBLISHED_FLOWS),
                anyString(), eq(start), eq(end), isNull(), eq(exception));
        });
    }

    @Test
    void testSaveGetFlowDetailsOk() throws Exception {
        // Given
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(3);
        String url = "http://api.example.com/flow/123";
        int paymentsCount = 10;

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(GdeService.EventTypes.GET_FLOW_DETAILS);
        mockEvento.setEsito(EsitoEvento.OK);

        when(eventoFdrMapper.createEventoOk(eq(testFr), eq(GdeService.EventTypes.GET_FLOW_DETAILS),
            anyString(), eq(start), eq(end))).thenReturn(mockEvento);

        // When
        gdeService.saveGetFlowDetailsOk(testFr, start, end, url, paymentsCount);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoOk(eq(testFr), eq(GdeService.EventTypes.GET_FLOW_DETAILS),
                anyString(), eq(start), eq(end));
            verify(eventoFdrMapper).setParametriRichiesta(eq(mockEvento), eq(url), eq("GET"), anyList());
        });
        assertThat(mockEvento.getSottotipoEvento()).contains("fdr=FDR-TEST-001");
        assertThat(mockEvento.getDettaglioEsito()).contains("10 payments");
    }

    @Test
    void testSaveGetFlowDetailsKo() throws Exception {
        // Given
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(3);
        String url = "http://api.example.com/flow/123";
        RestClientException exception = new RestClientException("Flow not found");

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(GdeService.EventTypes.GET_FLOW_DETAILS);
        mockEvento.setEsito(EsitoEvento.KO);

        when(eventoFdrMapper.createEventoKo(eq(testFr), eq(GdeService.EventTypes.GET_FLOW_DETAILS),
            anyString(), eq(start), eq(end), isNull(), eq(exception))).thenReturn(mockEvento);

        // When
        gdeService.saveGetFlowDetailsKo(testFr, start, end, url, exception);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoKo(eq(testFr), eq(GdeService.EventTypes.GET_FLOW_DETAILS),
                anyString(), eq(start), eq(end), isNull(), eq(exception));
        });
    }

    @Test
    void testSaveProcessFlowOk() throws Exception {
        // Given
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(10);
        int processedCount = 25;

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(GdeService.EventTypes.PROCESS_FLOW);
        mockEvento.setEsito(EsitoEvento.OK);

        when(eventoFdrMapper.createEventoOk(eq(testFr), eq(GdeService.EventTypes.PROCESS_FLOW),
            anyString(), eq(start), eq(end))).thenReturn(mockEvento);

        // When
        gdeService.saveProcessFlowOk(testFr, start, end, processedCount);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoOk(eq(testFr), eq(GdeService.EventTypes.PROCESS_FLOW),
                anyString(), eq(start), eq(end));
        });
        assertThat(mockEvento.getDettaglioEsito()).contains("25 payments");
    }

    @Test
    void testSaveProcessFlowKo() throws Exception {
        // Given
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(10);
        String errorMessage = "Processing failed: invalid data";

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(GdeService.EventTypes.PROCESS_FLOW);
        mockEvento.setEsito(EsitoEvento.KO);

        when(eventoFdrMapper.createEventoKo(eq(testFr), eq(GdeService.EventTypes.PROCESS_FLOW),
            anyString(), eq(start), eq(end), isNull(), isNull())).thenReturn(mockEvento);

        // When
        gdeService.saveProcessFlowKo(testFr, start, end, errorMessage);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoKo(eq(testFr), eq(GdeService.EventTypes.PROCESS_FLOW),
                anyString(), eq(start), eq(end), isNull(), isNull());
        });
        assertThat(mockEvento.getDettaglioEsito()).isEqualTo(errorMessage);
    }

    @Test
    void testSaveSaveFlowOk() throws Exception {
        // Given
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(2);

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(GdeService.EventTypes.SAVE_FLOW);
        mockEvento.setEsito(EsitoEvento.OK);

        when(eventoFdrMapper.createEventoOk(eq(testFr), eq(GdeService.EventTypes.SAVE_FLOW),
            anyString(), eq(start), eq(end))).thenReturn(mockEvento);

        // When
        gdeService.saveSaveFlowOk(testFr, start, end);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoOk(eq(testFr), eq(GdeService.EventTypes.SAVE_FLOW),
                anyString(), eq(start), eq(end));
        });
        assertThat(mockEvento.getDettaglioEsito()).isEqualTo("Flow saved successfully");
    }

    @Test
    void testSaveSaveFlowKo() throws Exception {
        // Given
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = start.plusSeconds(2);
        String errorMessage = "Database constraint violation";

        NuovoEvento mockEvento = new NuovoEvento();
        mockEvento.setTipoEvento(GdeService.EventTypes.SAVE_FLOW);
        mockEvento.setEsito(EsitoEvento.KO);

        when(eventoFdrMapper.createEventoKo(eq(testFr), eq(GdeService.EventTypes.SAVE_FLOW),
            anyString(), eq(start), eq(end), isNull(), isNull())).thenReturn(mockEvento);

        // When
        gdeService.saveSaveFlowKo(testFr, start, end, errorMessage);

        // Then
        await().untilAsserted(() -> {
            verify(eventoFdrMapper).createEventoKo(eq(testFr), eq(GdeService.EventTypes.SAVE_FLOW),
                anyString(), eq(start), eq(end), isNull(), isNull());
        });
        assertThat(mockEvento.getDettaglioEsito()).isEqualTo(errorMessage);
    }
}
