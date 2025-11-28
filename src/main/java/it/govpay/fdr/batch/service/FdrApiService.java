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
    public List<FlowByPSP> getAllPublishedFlows(
        String organizationId,
        Instant publishedGt
    ) throws RestClientException {

        log.debug("Fetching published flows for organization {} with publishedGt {}",
            organizationId, publishedGt);

        OffsetDateTime startTime = OffsetDateTime.now(ZoneOffset.UTC);
        List<FlowByPSP> allFlows = new ArrayList<>();
        Long currentPage = 1L;
        boolean hasMorePages = true;

        try {
            while (hasMorePages) {
                try {
                    log.debug("Calling API for organization {} page {}", organizationId, currentPage);

                    OffsetDateTime publishedGtOffset = publishedGt != null
                        ? OffsetDateTime.ofInstant(publishedGt, ZoneOffset.UTC)
                        : null;

                    PaginatedFlowsResponse response = organizationsApi.iOrganizationsControllerGetAllPublishedFlows(
                        organizationId,
                        null,           // flowDate
                        currentPage,    // page
                        null,           // pspId
                        publishedGtOffset,    // publishedGt
                        (long) pagoPAProperties.getPageSize()  // size
                    );

                    log.info("API call completed for organization {}, response received", organizationId);
                    log.info("Response for organization {}: data={}, metadata={}",
                        organizationId,
                        response.getData() != null ? response.getData().size() + " flows" : "null",
                        response.getMetadata());

                    if (response.getData() != null && !response.getData().isEmpty()) {
                        allFlows.addAll(response.getData());
                        log.info("Retrieved page {} with {} flows for organization {}",
                            currentPage, response.getData().size(), organizationId);
                    } else {
                        log.info("Page {} returned empty data for organization {}", currentPage, organizationId);
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
                    // Handle empty response (connection closed) - this is normal when no flows are available
                    if (e.getMessage() != null && e.getMessage().contains("closed")) {
                        log.info("No flows available for organization {} (empty response)", organizationId);
                        hasMorePages = false;
                    } else {
                        log.error("I/O error fetching flows for organization {} at page {}: {}",
                            organizationId, currentPage, e.getMessage());
                        throw new RestClientException("Failed to fetch flows for organization " + organizationId, e);
                    }
                } catch (Exception e) {
                    log.error("Error fetching flows for organization {} at page {}: {}",
                        organizationId, currentPage, e.getMessage());
                    log.error(e.getMessage(), e);
                    throw new RestClientException("Failed to fetch flows for organization " + organizationId, e);
                }
            }

            log.info("Retrieved total of {} flows for organization {}", allFlows.size(), organizationId);

            // Send success event to GDE
            if (gdeService != null) {
                OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);
                String url = pagoPAProperties.getBaseUrl() + Costanti.PATH_GET_ALL_PUBLISHED_FLOWS
                    .replace("{organizationId}", organizationId);
                gdeService.saveGetPublishedFlowsOk(organizationId, null,
                    publishedGt != null ? publishedGt.toString() : "all",
                    startTime, endTime, url, allFlows.size());
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
                    startTime, endTime, url, e);
            }
            throw e;
        }
    }

    /**
     * Get single published flow details
     */
    public SingleFlowResponse getSinglePublishedFlow(
        String organizationId,
        String fdr,
        Long revision,
        String pspId
    ) throws RestClientException {

        log.debug("Fetching flow details for organization={}, fdr={}, revision={}, pspId={}",
            organizationId, fdr, revision, pspId);

        OffsetDateTime startTime = OffsetDateTime.now(ZoneOffset.UTC);

        try {
            SingleFlowResponse response = organizationsApi.iOrganizationsControllerGetSinglePublishedFlow(
                fdr,
                organizationId,
                pspId,
                revision
            );

            log.info("Retrieved flow details for fdr={}: {}", fdr, response);

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

                gdeService.saveGetFlowDetailsOk(frForEvent, startTime, endTime, url, paymentsCount);
            }

            return response;

        } catch (Exception e) {
            log.error("Error fetching flow details for fdr={}: {}", fdr, e.getMessage());

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

                gdeService.saveGetFlowDetailsKo(frForEvent, startTime, endTime, url,
                    e instanceof RestClientException ? (RestClientException) e : new RestClientException(e.getMessage(), e));
            }

            throw new RestClientException("Failed to fetch flow details for " + fdr, e);
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

        log.debug("Fetching payments for flow: organization={}, fdr={}, revision={}, pspId={}",
            organizationId, fdr, revision, pspId);

        List<it.govpay.fdr.client.model.Payment> allPayments = new ArrayList<>();
        Long currentPage = 1L;
        boolean hasMorePages = true;

        while (hasMorePages) {
            try {
                PaginatedPaymentsResponse response = organizationsApi.iOrganizationsControllerGetPaymentsFromPublishedFlow(
                    fdr,
                    organizationId,
                    pspId,
                    revision,
                    currentPage,
                    (long) pagoPAProperties.getPageSize()
                );

                if (response.getData() != null && !response.getData().isEmpty()) {
                    allPayments.addAll(response.getData());
                    log.debug("Retrieved page {} with {} payments for fdr {}",
                        currentPage, response.getData().size(), fdr);
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

            } catch (Exception e) {
                log.error("Error fetching payments for fdr {} at page {}: {}",
                    fdr, currentPage, e.getMessage());
                throw new RestClientException("Failed to fetch payments for flow " + fdr, e);
            }
        }

        log.info("Retrieved total of {} payments for fdr {}", allPayments.size(), fdr);
        return allPayments;
    }
}
