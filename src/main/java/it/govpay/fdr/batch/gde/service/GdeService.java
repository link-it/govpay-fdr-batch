package it.govpay.fdr.batch.gde.service;

import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.gde.mapper.EventoFdrMapper;
import it.govpay.gde.client.api.EventiApi;
import it.govpay.gde.client.model.Header;
import it.govpay.gde.client.model.NuovoEvento;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service for sending FDR acquisition events to the GDE microservice.
 * <p>
 * This service tracks all FDR batch operations by sending events asynchronously
 * to GDE for monitoring, auditing, and debugging purposes.
 * <p>
 * Events include:
 * - GET_PUBLISHED_FLOWS: Fetching list of published flows
 * - GET_FLOW_DETAILS: Fetching single flow details
 * - PROCESS_FLOW: Processing flow data
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "govpay.gde.enabled", havingValue = "true", matchIfMissing = false)
public class GdeService {

    private final EventiApi eventiApi;
    private final EventoFdrMapper eventoFdrMapper;

    @Value("${govpay.gde.enabled:false}")
    private Boolean gdeEnabled;

    /**
     * Event type constants for FDR operations.
     */
    public static class EventTypes {
        public static final String GET_PUBLISHED_FLOWS = "GET_PUBLISHED_FLOWS";
        public static final String GET_FLOW_DETAILS = "GET_FLOW_DETAILS";
        public static final String PROCESS_FLOW = "PROCESS_FLOW";
        public static final String SAVE_FLOW = "SAVE_FLOW";
    }

    /**
     * Sends an event to GDE asynchronously.
     * <p>
     * If GDE is disabled or the event fails to send, the error is logged
     * but does not interrupt the batch processing.
     *
     * @param nuovoEvento Event to send
     */
    public void inviaEvento(NuovoEvento nuovoEvento) {
        if (!gdeEnabled) {
            log.debug("GDE disabled, skipping event: {}", nuovoEvento.getTipoEvento());
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                eventiApi.addEvento(nuovoEvento);
                log.info("Event {} sent successfully to GDE", nuovoEvento.getTipoEvento());
            } catch (Exception ex) {
                log.error("Failed to send event {} to GDE: {}",
                        nuovoEvento.getTipoEvento(), ex.getMessage(), ex);
            }
        });
    }

    /**
     * Records a successful GET_PUBLISHED_FLOWS operation.
     *
     * @param organizationId Organization identifier
     * @param pspId          PSP identifier (optional)
     * @param flowDate       Flow date filter
     * @param dataStart      Operation start time
     * @param dataEnd        Operation end time
     * @param url            Request URL
     * @param flowsCount     Number of flows retrieved
     */
    public void saveGetPublishedFlowsOk(String organizationId, String pspId, String flowDate,
                                         OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                         String url, int flowsCount) {
        String transactionId = UUID.randomUUID().toString();

        // Create event without Fr entity (list operation)
        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoOk(
                null, EventTypes.GET_PUBLISHED_FLOWS, transactionId, dataStart, dataEnd);

        nuovoEvento.setSottotipoEvento(String.format("org=%s,psp=%s,date=%s",
                organizationId, pspId != null ? pspId : "all", flowDate));
        nuovoEvento.setDettaglioEsito(String.format("Retrieved %d flows", flowsCount));

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", List.of());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, null, null);

        inviaEvento(nuovoEvento);
    }

    /**
     * Records a failed GET_PUBLISHED_FLOWS operation.
     *
     * @param organizationId Organization identifier
     * @param pspId          PSP identifier (optional)
     * @param flowDate       Flow date filter
     * @param dataStart      Operation start time
     * @param dataEnd        Operation end time
     * @param url            Request URL
     * @param exception      Exception that occurred
     */
    public void saveGetPublishedFlowsKo(String organizationId, String pspId, String flowDate,
                                         OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                         String url, RestClientException exception) {
        String transactionId = UUID.randomUUID().toString();

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoKo(
                null, EventTypes.GET_PUBLISHED_FLOWS, transactionId, dataStart, dataEnd,
                null, exception);

        nuovoEvento.setSottotipoEvento(String.format("org=%s,psp=%s,date=%s",
                organizationId, pspId != null ? pspId : "all", flowDate));

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", List.of());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, null, exception);

        inviaEvento(nuovoEvento);
    }

    /**
     * Records a successful GET_FLOW_DETAILS operation.
     *
     * @param fr             Fr entity with flow data
     * @param dataStart      Operation start time
     * @param dataEnd        Operation end time
     * @param url            Request URL
     * @param paymentsCount  Number of payments in flow
     */
    public void saveGetFlowDetailsOk(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                      String url, int paymentsCount) {
        String transactionId = UUID.randomUUID().toString();

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoOk(
                fr, EventTypes.GET_FLOW_DETAILS, transactionId, dataStart, dataEnd);

        nuovoEvento.setSottotipoEvento(String.format("fdr=%s,rev=%d",
                fr.getCodFlusso(), fr.getRevisione() != null ? fr.getRevisione() : 0));
        nuovoEvento.setDettaglioEsito(String.format("Retrieved flow with %d payments", paymentsCount));

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", List.of());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, null, null);

        inviaEvento(nuovoEvento);
    }

    /**
     * Records a failed GET_FLOW_DETAILS operation.
     *
     * @param fr             Fr entity with flow data
     * @param dataStart      Operation start time
     * @param dataEnd        Operation end time
     * @param url            Request URL
     * @param exception      Exception that occurred
     */
    public void saveGetFlowDetailsKo(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                      String url, RestClientException exception) {
        String transactionId = UUID.randomUUID().toString();

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoKo(
                fr, EventTypes.GET_FLOW_DETAILS, transactionId, dataStart, dataEnd,
                null, exception);

        nuovoEvento.setSottotipoEvento(String.format("fdr=%s,rev=%d",
                fr.getCodFlusso(), fr.getRevisione() != null ? fr.getRevisione() : 0));

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", List.of());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, null, exception);

        inviaEvento(nuovoEvento);
    }

    /**
     * Records a successful PROCESS_FLOW operation.
     *
     * @param fr             Fr entity with flow data
     * @param dataStart      Operation start time
     * @param dataEnd        Operation end time
     * @param processedCount Number of payments processed
     */
    public void saveProcessFlowOk(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                   int processedCount) {
        String transactionId = UUID.randomUUID().toString();

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoOk(
                fr, EventTypes.PROCESS_FLOW, transactionId, dataStart, dataEnd);

        nuovoEvento.setSottotipoEvento(String.format("fdr=%s", fr.getCodFlusso()));
        nuovoEvento.setDettaglioEsito(String.format("Processed %d payments", processedCount));

        inviaEvento(nuovoEvento);
    }

    /**
     * Records a failed PROCESS_FLOW operation.
     *
     * @param fr             Fr entity with flow data
     * @param dataStart      Operation start time
     * @param dataEnd        Operation end time
     * @param errorMessage   Error message
     */
    public void saveProcessFlowKo(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                   String errorMessage) {
        String transactionId = UUID.randomUUID().toString();

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoKo(
                fr, EventTypes.PROCESS_FLOW, transactionId, dataStart, dataEnd,
                null, null);

        nuovoEvento.setSottotipoEvento(String.format("fdr=%s", fr.getCodFlusso()));
        nuovoEvento.setDettaglioEsito(errorMessage);

        inviaEvento(nuovoEvento);
    }

    /**
     * Records a successful SAVE_FLOW operation.
     *
     * @param fr             Fr entity with flow data
     * @param dataStart      Operation start time
     * @param dataEnd        Operation end time
     */
    public void saveSaveFlowOk(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd) {
        String transactionId = UUID.randomUUID().toString();

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoOk(
                fr, EventTypes.SAVE_FLOW, transactionId, dataStart, dataEnd);

        nuovoEvento.setSottotipoEvento(String.format("fdr=%s", fr.getCodFlusso()));
        nuovoEvento.setDettaglioEsito("Flow saved successfully");

        inviaEvento(nuovoEvento);
    }

    /**
     * Records a failed SAVE_FLOW operation.
     *
     * @param fr             Fr entity with flow data
     * @param dataStart      Operation start time
     * @param dataEnd        Operation end time
     * @param errorMessage   Error message
     */
    public void saveSaveFlowKo(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                String errorMessage) {
        String transactionId = UUID.randomUUID().toString();

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoKo(
                fr, EventTypes.SAVE_FLOW, transactionId, dataStart, dataEnd,
                null, null);

        nuovoEvento.setSottotipoEvento(String.format("fdr=%s", fr.getCodFlusso()));
        nuovoEvento.setDettaglioEsito(errorMessage);

        inviaEvento(nuovoEvento);
    }
}
