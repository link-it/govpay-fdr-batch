package it.govpay.fdr.batch.gde.service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.govpay.common.client.gde.HttpDataHolder;
import it.govpay.common.configurazione.service.ConfigurazioneService;
import it.govpay.common.gde.AbstractGdeService;
import it.govpay.common.gde.GdeEventInfo;
import it.govpay.common.gde.GdeUtils;
import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.gde.mapper.EventoFdrMapper;
import it.govpay.gde.client.beans.DatiPagoPA;
import it.govpay.gde.client.beans.NuovoEvento;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for sending FDR acquisition events to the GDE microservice.
 * <p>
 * Extends {@link AbstractGdeService} from govpay-common for RestTemplate-based
 * async event sending via ConfigurazioneService.
 * <p>
 * Events include:
 * - IOrganizationsController_getAllPublishedFlows: Fetching list of published flows
 * - IOrganizationsController_getSinglePublishedFlow: Fetching single flow details
 * - PROCESS_FLOW: Processing flow data (internal operation)
 * - SAVE_FLOW: Saving flow data (internal operation)
 */
@Slf4j
@Service
public class GdeService extends AbstractGdeService {

	private final EventoFdrMapper eventoFdrMapper;
    private final ConfigurazioneService configurazioneService;

    public GdeService(ObjectMapper objectMapper,
                      @Qualifier("asyncHttpExecutor") Executor asyncHttpExecutor,
                      ConfigurazioneService configurazioneService,
                      EventoFdrMapper eventoFdrMapper) {
        super(objectMapper, asyncHttpExecutor, configurazioneService);
        this.eventoFdrMapper = eventoFdrMapper;
        this.configurazioneService = configurazioneService;
    }

    @Override
    protected String getGdeEndpoint() {
        return configurazioneService.getServizioGDE().getUrl() + "/eventi";
    }

    @Override
    protected NuovoEvento convertToGdeEvent(GdeEventInfo eventInfo) {
        throw new UnsupportedOperationException(
                "GdeService usa sendEventAsync(NuovoEvento) direttamente, non il pattern GdeEventInfo");
    }

    /**
     * Sends an event to GDE asynchronously using the inherited async executor
     * and RestTemplate from ConfigurazioneService.
     *
     * @param nuovoEvento Event to send
     */
    public void sendEventAsync(NuovoEvento nuovoEvento) {
        if (!isAbilitato()) {
            log.debug("Connettore GDE disabilitato, evento {} non inviato", nuovoEvento.getTipoEvento());
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                getGdeRestTemplate().postForEntity(getGdeEndpoint(), nuovoEvento, Void.class);
                log.debug("Evento {} inviato con successo al GDE", nuovoEvento.getTipoEvento());
            } catch (Exception ex) {
                log.warn("Impossibile inviare evento {} al GDE (il batch continua normalmente): {}",
                        nuovoEvento.getTipoEvento(), ex.getMessage());
                log.debug("Dettaglio errore GDE:", ex);
            } finally {
                HttpDataHolder.clear();
            }
        }, this.asyncExecutor);
    }

    /**
     * Records a successful GET_PUBLISHED_FLOWS operation.
     */
    public void saveGetPublishedFlowsOk(String organizationId, String pspId,
                                         OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                          int flowsCount, ResponseEntity<?> responseEntity,
                                          String url) {
        String transactionId = UUID.randomUUID().toString();
        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoOk(
                null, Costanti.OPERATION_GET_ALL_PUBLISHED_FLOWS, transactionId, dataStart, dataEnd);

        nuovoEvento.setIdDominio(organizationId);

        DatiPagoPA datiPagoPA = new DatiPagoPA();
        datiPagoPA.setIdDominio(organizationId);
        datiPagoPA.setIdPsp(pspId);
        nuovoEvento.setDatiPagoPA(datiPagoPA);

        nuovoEvento.setDettaglioEsito(String.format("Retrieved %d flows", flowsCount));

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", GdeUtils.getCapturedRequestHeadersAsGdeHeaders());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, responseEntity, null);

        setResponsePayload(nuovoEvento, responseEntity, null);

        sendEventAsync(nuovoEvento);
    }

    /**
     * Records a failed GET_PUBLISHED_FLOWS operation.
     */
    public void saveGetPublishedFlowsKo(String organizationId, String pspId, 
                                         OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                         ResponseEntity<?> responseEntity, RestClientException exception,
                                         String url) {
        String transactionId = UUID.randomUUID().toString();

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoKo(
                null, Costanti.OPERATION_GET_ALL_PUBLISHED_FLOWS, transactionId, dataStart, dataEnd,
                null, exception);

        nuovoEvento.setIdDominio(organizationId);

        DatiPagoPA datiPagoPA = new DatiPagoPA();
        datiPagoPA.setIdDominio(organizationId);
        datiPagoPA.setIdPsp(pspId);
        nuovoEvento.setDatiPagoPA(datiPagoPA);

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", GdeUtils.getCapturedRequestHeadersAsGdeHeaders());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, null, exception);

        setResponsePayload(nuovoEvento, responseEntity, exception);

        sendEventAsync(nuovoEvento);
    }

    /**
     * Records a successful GET_FLOW_DETAILS operation.
     */
    public void saveGetFlowDetailsOk(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                      int paymentsCount, ResponseEntity<?> responseEntity,
                                      String pagoPABaseUrl) {
        String transactionId = UUID.randomUUID().toString();

        String url = buildFlowUrl(pagoPABaseUrl, Costanti.PATH_GET_SINGLE_PUBLISHED_FLOW, fr);

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoOk(
                fr, Costanti.OPERATION_GET_SINGLE_PUBLISHED_FLOW, transactionId, dataStart, dataEnd);

        nuovoEvento.setDettaglioEsito(String.format("Retrieved flow with %d payments", paymentsCount));

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", GdeUtils.getCapturedRequestHeadersAsGdeHeaders());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, responseEntity, null);

        setResponsePayload(nuovoEvento, responseEntity, null);

        sendEventAsync(nuovoEvento);
    }

    /**
     * Records a failed GET_FLOW_DETAILS operation.
     */
    public void saveGetFlowDetailsKo(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                      ResponseEntity<?> responseEntity, RestClientException exception,
                                      String pagoPABaseUrl) {
        String transactionId = UUID.randomUUID().toString();

        String url = buildFlowUrl(pagoPABaseUrl, Costanti.PATH_GET_SINGLE_PUBLISHED_FLOW, fr);

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoKo(
                fr, Costanti.OPERATION_GET_SINGLE_PUBLISHED_FLOW, transactionId, dataStart, dataEnd,
                null, exception);

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", GdeUtils.getCapturedRequestHeadersAsGdeHeaders());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, null, exception);

        setResponsePayload(nuovoEvento, responseEntity, exception);

        sendEventAsync(nuovoEvento);
    }

    /**
     * Records a successful GET_PAYMENTS_FROM_PUBLISHED_FLOW operation.
     */
    public void saveGetPaymentsOk(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                   int paymentsCount, ResponseEntity<?> responseEntity,
                                   String pagoPABaseUrl) {
        String transactionId = UUID.randomUUID().toString();

        String url = buildFlowUrl(pagoPABaseUrl, Costanti.PATH_GET_PAYMENTS_FROM_PUBLISHED_FLOW, fr);

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoOk(
                fr, Costanti.OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW, transactionId, dataStart, dataEnd);

        nuovoEvento.setDettaglioEsito(String.format("Retrieved %d payments", paymentsCount));

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", GdeUtils.getCapturedRequestHeadersAsGdeHeaders());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, responseEntity, null);

        setResponsePayload(nuovoEvento, responseEntity, null);

        sendEventAsync(nuovoEvento);
    }

    /**
     * Records a failed GET_PAYMENTS_FROM_PUBLISHED_FLOW operation.
     */
    public void saveGetPaymentsKo(Fr fr, OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                   ResponseEntity<?> responseEntity, RestClientException exception,
                                   String pagoPABaseUrl) {
        String transactionId = UUID.randomUUID().toString();

        String url = buildFlowUrl(pagoPABaseUrl, Costanti.PATH_GET_PAYMENTS_FROM_PUBLISHED_FLOW, fr);

        NuovoEvento nuovoEvento = eventoFdrMapper.createEventoKo(
                fr, Costanti.OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW, transactionId, dataStart, dataEnd,
                null, exception);

        eventoFdrMapper.setParametriRichiesta(nuovoEvento, url, "GET", GdeUtils.getCapturedRequestHeadersAsGdeHeaders());
        eventoFdrMapper.setParametriRisposta(nuovoEvento, dataEnd, null, exception);

        setResponsePayload(nuovoEvento, responseEntity, exception);

        sendEventAsync(nuovoEvento);
    }

    /**
     * Sets the response payload on the event using the common GdeUtils.extractResponsePayload().
     */
    private void setResponsePayload(NuovoEvento nuovoEvento, ResponseEntity<?> responseEntity,
                                     RestClientException exception) {
        if (nuovoEvento.getParametriRisposta() != null) {
            nuovoEvento.getParametriRisposta().setPayload(
                extractResponsePayload(responseEntity, exception));
        }
    }

    /**
     * Builds the URL for getAllPublishedFlows using GdeUtils.buildUrl().
     */
    public String buildGetAllPublishedFlowsUrl(String pagoPABaseUrl, String organizationId, String flowDate) {
        Map<String, String> pathParams = Map.of("{organizationId}", organizationId);
        Map<String, String> queryParams = (flowDate != null && !flowDate.isEmpty())
            ? Map.of("publishedGt", flowDate) : null;
        return GdeUtils.buildUrl(pagoPABaseUrl, Costanti.PATH_GET_ALL_PUBLISHED_FLOWS, pathParams, queryParams);
    }

    /**
     * Builds the URL for flow-specific operations (details, payments) using GdeUtils.buildUrl().
     */
    private String buildFlowUrl(String pagoPABaseUrl, String path, Fr fr) {
        return GdeUtils.buildUrl(pagoPABaseUrl, path,
            Map.of(
                "{organizationId}", fr.getCodDominio(),
                "{fdr}", fr.getCodFlusso(),
                "{revision}", String.valueOf(fr.getRevisione()),
                "{pspId}", fr.getCodPsp()
            ),
            null);
    }
}
