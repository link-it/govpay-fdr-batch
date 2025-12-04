package it.govpay.fdr.batch.gde.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.config.PagoPAProperties;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.gde.mapper.EventoFdrMapper;
import it.govpay.fdr.batch.gde.utils.GdeUtils;
import it.govpay.gde.client.api.EventiApi;
import it.govpay.gde.client.model.NuovoEvento;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for sending FDR acquisition events to the GDE microservice.
 * <p>
 * This service tracks all FDR batch operations by sending events asynchronously
 * to GDE for monitoring, auditing, and debugging purposes.
 * <p>
 * Events include:
 * - IOrganizationsController_getAllPublishedFlows: Fetching list of published flows
 * - IOrganizationsController_getSinglePublishedFlow: Fetching single flow details
 * - PROCESS_FLOW: Processing flow data (internal operation)
 * - SAVE_FLOW: Saving flow data (internal operation)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "govpay.gde.enabled", havingValue = "true", matchIfMissing = false)
public class GdeService {

    private static final String PLACEHOLDER_PSP_ID = "{pspId}";
	private static final String PLACEHOLDER_REVISION = "{revision}";
	private static final String PLACEHOLDER_FDR = "{fdr}";
	private static final String PLACEHOLDER_ORGANIZATION_ID = "{organizationId}";
	
	private final EventiApi eventiApi;
    private final EventoFdrMapper eventoFdrMapper;
    private final ObjectMapper objectMapper;
    private final PagoPAProperties pagoPAProperties;
    
    @Value("${govpay.gde.enabled:false}")
    private Boolean gdeEnabled;
    
    /**
     * Sends an event to GDE asynchronously.
     * <p>
     * If GDE is disabled or the event fails to send, the error is logged
     * but does not interrupt the batch processing.
     *
     * @param nuovoEvento Event to send
     */
    public void inviaEvento(NuovoEvento nuovoEvento) {
        if (Boolean.FALSE.equals(gdeEnabled)) {
            log.debug("GDE disabilitato, salto evento: {}", nuovoEvento.getTipoEvento());
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                eventiApi.addEvento(nuovoEvento);
                log.info("Evento {} inviato con successo al GDE", nuovoEvento.getTipoEvento());
            } catch (Exception ex) {
                log.error("Fallito l'invio dell'evento {} al GDE: {}",
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
                                          int flowsCount, ResponseEntity<?> responseEntity) {
        String transactionId = UUID.randomUUID().toString();
        String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_ALL_PUBLISHED_FLOWS
                .replace(PLACEHOLDER_ORGANIZATION_ID, organizationId);
        // Create event without Fr entity (list operation)
        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoOk(
                null, Costanti.OPERATION_GET_ALL_PUBLISHED_FLOWS, transactionId, dataStart, dataEnd);

        // Always set idDominio - it's always known
        nuovoEvento.setIdDominio(organizationId);

        // Always set datiPagoPA with idDominio and idPsp
        it.govpay.gde.client.model.DatiPagoPA datiPagoPA = new it.govpay.gde.client.model.DatiPagoPA();
        datiPagoPA.setIdDominio(organizationId);
        datiPagoPA.setIdPsp(pspId);
        nuovoEvento.setDatiPagoPA(datiPagoPA);

        nuovoEvento.setDettaglioEsito(String.format("Retrieved %d flows", flowsCount));

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", List.of());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, responseEntity, null);
        
        GdeUtils.serializzaPayload(this.objectMapper, nuovoEvento, responseEntity, null);

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
                                         ResponseEntity<?> responseEntity, RestClientException exception) {
        String transactionId = UUID.randomUUID().toString();
        
        String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_ALL_PUBLISHED_FLOWS
                .replace(PLACEHOLDER_ORGANIZATION_ID, organizationId);

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoKo(
                null, Costanti.OPERATION_GET_ALL_PUBLISHED_FLOWS, transactionId, dataStart, dataEnd,
                null, exception);

        // Always set idDominio - it's always known
        nuovoEvento.setIdDominio(organizationId);

        // Always set datiPagoPA with idDominio and idPsp
        it.govpay.gde.client.model.DatiPagoPA datiPagoPA = new it.govpay.gde.client.model.DatiPagoPA();
        datiPagoPA.setIdDominio(organizationId);
        datiPagoPA.setIdPsp(pspId);
        nuovoEvento.setDatiPagoPA(datiPagoPA);


        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", List.of());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, null, exception);
        
        GdeUtils.serializzaPayload(this.objectMapper, nuovoEvento, responseEntity, exception);

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
     * @param responseEntity HTTP response entity with payload
     */
    public void saveGetFlowDetailsOk(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                      int paymentsCount, ResponseEntity<?> responseEntity) {
        String transactionId = UUID.randomUUID().toString();
        
        String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_SINGLE_PUBLISHED_FLOW
                .replace(PLACEHOLDER_ORGANIZATION_ID, fr.getCodDominio())
                .replace(PLACEHOLDER_FDR, fr.getCodFlusso())
                .replace(PLACEHOLDER_REVISION, String.valueOf(fr.getRevisione()))
                .replace(PLACEHOLDER_PSP_ID, fr.getCodPsp());

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoOk(
                fr, Costanti.OPERATION_GET_SINGLE_PUBLISHED_FLOW, transactionId, dataStart, dataEnd);

        nuovoEvento.setDettaglioEsito(String.format("Retrieved flow with %d payments", paymentsCount));

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", List.of());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, responseEntity, null);
        
        GdeUtils.serializzaPayload(this.objectMapper, nuovoEvento, responseEntity, null);

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
                                      ResponseEntity<?> responseEntity, RestClientException exception) {
        String transactionId = UUID.randomUUID().toString();
        
        String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_SINGLE_PUBLISHED_FLOW
                .replace(PLACEHOLDER_ORGANIZATION_ID, fr.getCodDominio())
                .replace(PLACEHOLDER_FDR, fr.getCodFlusso())
                .replace(PLACEHOLDER_REVISION, String.valueOf(fr.getRevisione()))
                .replace(PLACEHOLDER_PSP_ID, fr.getCodPsp());

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoKo(
                fr, Costanti.OPERATION_GET_SINGLE_PUBLISHED_FLOW, transactionId, dataStart, dataEnd,
                null, exception);


        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", List.of());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, null, exception);

        GdeUtils.serializzaPayload(this.objectMapper, nuovoEvento, responseEntity, exception);

        inviaEvento(nuovoEvento);
    }

    /**
     * Records a successful GET_PAYMENTS_FROM_PUBLISHED_FLOW operation.
     *
     * @param fr             Fr entity with flow data
     * @param dataStart      Operation start time
     * @param dataEnd        Operation end time
     * @param url            Request URL
     * @param paymentsCount  Number of payments retrieved
     * @param responseEntity HTTP response entity with payload
     */
    public void saveGetPaymentsOk(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                   int paymentsCount, ResponseEntity<?> responseEntity) {
        String transactionId = UUID.randomUUID().toString();
        
        String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_PAYMENTS_FROM_PUBLISHED_FLOW
                .replace(PLACEHOLDER_ORGANIZATION_ID, fr.getCodDominio())
                .replace(PLACEHOLDER_FDR, fr.getCodFlusso())
                .replace(PLACEHOLDER_REVISION, String.valueOf(fr.getRevisione()))
                .replace(PLACEHOLDER_PSP_ID, fr.getCodPsp());

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoOk(
                fr, Costanti.OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW, transactionId, dataStart, dataEnd);

        nuovoEvento.setDettaglioEsito(String.format("Retrieved %d payments", paymentsCount));

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", List.of());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, responseEntity, null);

        GdeUtils.serializzaPayload(this.objectMapper, nuovoEvento, responseEntity, null);

        inviaEvento(nuovoEvento);
    }

    /**
     * Records a failed GET_PAYMENTS_FROM_PUBLISHED_FLOW operation.
     *
     * @param fr             Fr entity with flow data
     * @param dataStart      Operation start time
     * @param dataEnd        Operation end time
     * @param url            Request URL
     * @param responseEntity HTTP response entity with payload
     * @param exception      Exception that occurred
     */
    public void saveGetPaymentsKo(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                   ResponseEntity<?> responseEntity, RestClientException exception) {
        String transactionId = UUID.randomUUID().toString();
        
        String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_PAYMENTS_FROM_PUBLISHED_FLOW
                .replace(PLACEHOLDER_ORGANIZATION_ID, fr.getCodDominio())
                .replace(PLACEHOLDER_FDR, fr.getCodFlusso())
                .replace(PLACEHOLDER_REVISION, String.valueOf(fr.getRevisione()))
                .replace(PLACEHOLDER_PSP_ID, fr.getCodPsp());

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoKo(
                fr, Costanti.OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW, transactionId, dataStart, dataEnd,
                null, exception);

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", List.of());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, null, exception);

        GdeUtils.serializzaPayload(this.objectMapper, nuovoEvento, responseEntity, exception);

        inviaEvento(nuovoEvento);
    }
}
