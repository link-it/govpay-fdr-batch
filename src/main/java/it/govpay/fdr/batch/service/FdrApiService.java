package it.govpay.fdr.batch.service;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.config.PagoPAProperties;
import it.govpay.fdr.batch.gde.service.GdeService;
import it.govpay.fdr.client.ApiClient;
import it.govpay.fdr.client.api.OrganizationsApi;
import it.govpay.fdr.client.model.FlowByPSP;
import it.govpay.fdr.client.model.PaginatedFlowsResponse;
import it.govpay.fdr.client.model.PaginatedPaymentsResponse;
import it.govpay.fdr.client.model.SingleFlowResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for interacting with pagoPA FDR API
 */
@Service
@Slf4j
public class FdrApiService {

    private final OrganizationsApi organizationsApi;
    private final PagoPAProperties pagoPAProperties;
    private final GdeService gdeService;

    public FdrApiService(RestTemplate fdrApiRestTemplate, PagoPAProperties pagoPAProperties,
                         @Autowired(required = false) GdeService gdeService) {
        this.pagoPAProperties = pagoPAProperties;
        this.gdeService = gdeService;

        ApiClient apiClient = new ApiClient(fdrApiRestTemplate);
        apiClient.setBasePath(pagoPAProperties.getBaseUrl());
        apiClient.setDebugging(pagoPAProperties.isDebugging());
        this.organizationsApi = new OrganizationsApi(apiClient);
    }

    /**
     * Get all published flows for a domain with pagination
     */
    public List<FlowByPSP> getAllPublishedFlows(String organizationId, Instant publishedGt) throws RestClientException {

        log.debug("Recupero dei flussi pubblicati per l'organizzazione {} con publishedGt {}",
            organizationId, publishedGt);

        OffsetDateTime startTime = OffsetDateTime.now(ZoneOffset.UTC);
        List<FlowByPSP> allFlows = new ArrayList<>();
        Long currentPage = 1L;
        boolean hasMorePages = true;
        ResponseEntity<PaginatedFlowsResponse> lastResponseEntity = null;

        try {
            while (hasMorePages) {
                try {
                    log.debug("Chiamata API per l'organizzazione {} pagina {}", organizationId, currentPage);

                    OffsetDateTime publishedGtOffset = publishedGt != null
                        ? OffsetDateTime.ofInstant(publishedGt, ZoneOffset.UTC)
                        : null;

                    lastResponseEntity = organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
                        organizationId,
                        null,           // flowDate
                        currentPage,    // page
                        null,           // pspId
                        publishedGtOffset,    // publishedGt
                        (long) pagoPAProperties.getPageSize()  // size
                    );

                    PaginatedFlowsResponse response = lastResponseEntity.getBody();

                    log.info("Chiamata API completata per l'organizzazione {}, risposta ricevuta", organizationId);
                    log.info("Risposta per l'organizzazione {}: data={}, metadata={}",
                        organizationId,
                        response.getData() != null ? response.getData().size() + " flussi" : "null",
                        response.getMetadata());

                    if (response.getData() != null && !response.getData().isEmpty()) {
                        allFlows.addAll(response.getData());
                        log.info("Recuperata pagina {} con {} flussi per l'organizzazione {}",
                            currentPage, response.getData().size(), organizationId);
                    } else {
                        log.info("Pagina {} ha restituito dati vuoti per l'organizzazione {}", currentPage, organizationId);
                    }

                    // Check if there are more pages
                    if (response.getMetadata() != null &&
                        response.getMetadata().getPageNumber() != null &&
                        response.getMetadata().getTotPage() != null) {
                        hasMorePages = response.getMetadata().getPageNumber() < response.getMetadata().getTotPage();
                        currentPage++;
                    } else {
                        hasMorePages = false;
                    }

                } catch (org.springframework.web.client.ResourceAccessException e) {
                    // Gestione risposta vuota (connessione chiusa) - normale quando non ci sono flussi disponibili
                    if (e.getMessage() != null && e.getMessage().contains("closed")) {
                        log.info("Nessun flusso disponibile per l'organizzazione {} (risposta vuota)", organizationId);
                        hasMorePages = false;
                    } else {
                        log.error("Errore I/O nel recupero dei flussi per l'organizzazione {} alla pagina {}: {}",
                            organizationId, currentPage, e.getMessage());
                        throw new RestClientException("Fallito il recupero dei flussi per l'organizzazione " + organizationId, e);
                    }
                } catch (Exception e) {
                    log.error("Errore nel recupero dei flussi per l'organizzazione {} alla pagina {}: {}",
                        organizationId, currentPage, e.getMessage());
                    log.error(e.getMessage(), e);
                    throw new RestClientException("Fallito il recupero dei flussi per l'organizzazione " + organizationId, e);
                }
            }

            log.info("Recuperati in totale {} flussi per l'organizzazione {}", allFlows.size(), organizationId);

            // Send success event to GDE
            if (gdeService != null) {
                OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);
                String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_ALL_PUBLISHED_FLOWS
                    .replace("{organizationId}", organizationId);
                gdeService.saveGetPublishedFlowsOk(organizationId, null,
                    publishedGt != null ? publishedGt.toString() : "all",
                    startTime, endTime, url, allFlows.size(), lastResponseEntity);
            }

            return allFlows;

        } catch (RestClientException e) {
            // Send failure event to GDE
            if (gdeService != null) {
                OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);
                String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_ALL_PUBLISHED_FLOWS
                    .replace("{organizationId}", organizationId);
                gdeService.saveGetPublishedFlowsKo(organizationId, null,
                    publishedGt != null ? publishedGt.toString() : "all",
                    startTime, endTime, url, lastResponseEntity, e);
            }
            throw e;
        }
    }

    /**
     * Get single published flow details
     */
    public SingleFlowResponse getSinglePublishedFlow(String organizationId, String fdr, Long revision, String pspId) throws RestClientException {

        log.debug("Recupero dettagli flusso per organization={}, fdr={}, revision={}, pspId={}", organizationId, fdr, revision, pspId);

        OffsetDateTime startTime = OffsetDateTime.now(ZoneOffset.UTC);
        ResponseEntity<SingleFlowResponse> responseEntity = null;
        try {
            responseEntity = organizationsApi.iOrganizationsControllerGetSinglePublishedFlowWithHttpInfo(
                fdr,
                organizationId,
                pspId,
                revision
            );

            SingleFlowResponse response = responseEntity.getBody();
            log.info("Recuperati dettagli flusso per fdr={}: {}", fdr, response);

            // Send success event to GDE
            if (gdeService != null && response != null) {
                OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);
                String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_SINGLE_PUBLISHED_FLOW
                    .replace("{organizationId}", organizationId)
                    .replace("{fdr}", fdr)
                    .replace("{revision}", String.valueOf(revision))
                    .replace("{pspId}", pspId);

                // Create minimal Fr object for event tracking
                it.govpay.fdr.batch.entity.Fr frForEvent = it.govpay.fdr.batch.entity.Fr.builder()
                    .codFlusso(fdr)
                    .codPsp(pspId)
                    .codDominio(organizationId)
                    .revisione(revision)
                    .build();

                int paymentsCount = (response.getTotPayments() != null)
                    ? response.getTotPayments().intValue() : 0;

                gdeService.saveGetFlowDetailsOk(frForEvent, startTime, endTime, url, paymentsCount, responseEntity);
            }

            return response;

        } catch (Exception e) {
            log.error("Errore nel recupero dei dettagli del flusso per fdr={}: {}", fdr, e.getMessage());

            // Send failure event to GDE
            if (gdeService != null) {
                OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);
                String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_SINGLE_PUBLISHED_FLOW
                    .replace("{organizationId}", organizationId)
                    .replace("{fdr}", fdr)
                    .replace("{revision}", String.valueOf(revision))
                    .replace("{pspId}", pspId);

                it.govpay.fdr.batch.entity.Fr frForEvent = it.govpay.fdr.batch.entity.Fr.builder()
                    .codFlusso(fdr)
                    .codPsp(pspId)
                    .codDominio(organizationId)
                    .revisione(revision)
                    .build();

                gdeService.saveGetFlowDetailsKo(frForEvent, startTime, endTime, url, responseEntity,
                    e instanceof RestClientException restClientException ? restClientException : new RestClientException(e.getMessage(), e));
            }

            throw new RestClientException("Fallito il recupero dei dettagli del flusso per " + fdr, e);
        }
    }

    /**
     * Get payments from a published flow with pagination
     */
    public List<it.govpay.fdr.client.model.Payment> getPaymentsFromPublishedFlow(
        String organizationId,
        String fdr,
        Long revision,
        String pspId
    ) throws RestClientException {

        log.debug("Recupero pagamenti per il flusso: organization={}, fdr={}, revision={}, pspId={}",
            organizationId, fdr, revision, pspId);

        OffsetDateTime startTime = OffsetDateTime.now(ZoneOffset.UTC);
        List<it.govpay.fdr.client.model.Payment> allPayments = new ArrayList<>();
        Long currentPage = 1L;
        boolean hasMorePages = true;
        ResponseEntity<PaginatedPaymentsResponse> lastResponseEntity = null;

        try {
            while (hasMorePages) {
                try {
                    lastResponseEntity = organizationsApi.iOrganizationsControllerGetPaymentsFromPublishedFlowWithHttpInfo(
                        fdr,
                        organizationId,
                        pspId,
                        revision,
                        currentPage,
                        (long) pagoPAProperties.getPageSize()
                    );

                    PaginatedPaymentsResponse response = lastResponseEntity.getBody();

                    if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                        allPayments.addAll(response.getData());
                        log.debug("Recuperata pagina {} con {} pagamenti per fdr {}",
                            currentPage, response.getData().size(), fdr);
                    }

                    // Check if there are more pages
                    if (response != null && response.getMetadata() != null &&
                        response.getMetadata().getPageNumber() != null &&
                        response.getMetadata().getTotPage() != null) {
                        hasMorePages = response.getMetadata().getPageNumber() < response.getMetadata().getTotPage();
                        currentPage++;
                    } else {
                        hasMorePages = false;
                    }

                } catch (Exception e) {
                    log.error("Errore nel recupero dei pagamenti per fdr {} alla pagina {}: {}",
                        fdr, currentPage, e.getMessage());
                    throw new RestClientException("Fallito il recupero dei pagamenti per il flusso " + fdr, e);
                }
            }

            log.info("Recuperati in totale {} pagamenti per fdr {}", allPayments.size(), fdr);

            // Send success event to GDE
            if (gdeService != null) {
                OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);
                String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_PAYMENTS_FROM_PUBLISHED_FLOW
                    .replace("{organizationId}", organizationId)
                    .replace("{fdr}", fdr)
                    .replace("{revision}", String.valueOf(revision))
                    .replace("{pspId}", pspId);

                // Create minimal Fr object for event tracking
                it.govpay.fdr.batch.entity.Fr frForEvent = it.govpay.fdr.batch.entity.Fr.builder()
                    .codFlusso(fdr)
                    .codPsp(pspId)
                    .codDominio(organizationId)
                    .revisione(revision)
                    .build();

                gdeService.saveGetPaymentsOk(frForEvent, startTime, endTime, url, allPayments.size(), lastResponseEntity);
            }

            return allPayments;

        } catch (RestClientException e) {
            // Send failure event to GDE
            if (gdeService != null) {
                OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);
                String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_PAYMENTS_FROM_PUBLISHED_FLOW
                    .replace("{organizationId}", organizationId)
                    .replace("{fdr}", fdr)
                    .replace("{revision}", String.valueOf(revision))
                    .replace("{pspId}", pspId);

                it.govpay.fdr.batch.entity.Fr frForEvent = it.govpay.fdr.batch.entity.Fr.builder()
                    .codFlusso(fdr)
                    .codPsp(pspId)
                    .codDominio(organizationId)
                    .revisione(revision)
                    .build();

                gdeService.saveGetPaymentsKo(frForEvent, startTime, endTime, url, lastResponseEntity, e);
            }
            throw e;
        }
    }
}
