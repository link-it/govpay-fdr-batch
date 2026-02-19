package it.govpay.fdr.batch.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import it.govpay.common.client.model.Connettore;
import it.govpay.common.client.service.ConnettoreService;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.fdr.batch.config.BatchProperties;
import it.govpay.fdr.batch.config.FdrApiClientConfig;
import it.govpay.fdr.batch.gde.service.GdeService;
import it.govpay.fdr.client.ApiClient;
import it.govpay.fdr.client.api.OrganizationsApi;
import it.govpay.fdr.client.model.FlowByPSP;
import it.govpay.fdr.client.model.PaginatedFlowsResponse;
import it.govpay.fdr.client.model.PaginatedPaymentsResponse;
import it.govpay.fdr.client.model.SingleFlowResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for interacting with pagoPA FDR API.
 * Resolves the FDR connector per-domain via IntermediarioRepository,
 * following the chain: DominioEntity -> StazioneEntity -> IntermediarioEntity.codConnettoreFr
 */
@Service
@Slf4j
public class FdrApiService {

    private final BatchProperties batchProperties;
    private final GdeService gdeService;
    private final ZoneId applicationZoneId;
    private final IntermediarioRepository intermediarioRepository;
    private final ConnettoreService connettoreService;
    private final FdrApiClientConfig fdrApiClientConfig;

    /** Cache of OrganizationsApi instances keyed by connector code */
    private final ConcurrentHashMap<String, OrganizationsApi> apiCache = new ConcurrentHashMap<>();

    public FdrApiService(BatchProperties batchProperties,
                         ConnettoreService connettoreService,
                         IntermediarioRepository intermediarioRepository,
                         GdeService gdeService,
                         ZoneId applicationZoneId,
                         FdrApiClientConfig fdrApiClientConfig) {
        this.batchProperties = batchProperties;
        this.connettoreService = connettoreService;
        this.intermediarioRepository = intermediarioRepository;
        this.gdeService = gdeService;
        this.applicationZoneId = applicationZoneId;
        this.fdrApiClientConfig = fdrApiClientConfig;
    }

    /**
     * Clears the cached OrganizationsApi instances.
     * Should be called when connector configuration changes in the database.
     */
    public void clearCache() {
        int size = apiCache.size();
        apiCache.clear();
        log.info("Cache connettori API svuotata ({} entries rimosse)", size);
    }

    /**
     * Resolves the connector code for the given domain via IntermediarioRepository.
     */
    private String resolveConnectorCode(String codDominio) {
        Optional<IntermediarioEntity> intermediarioOpt = intermediarioRepository.findByCodDominio(codDominio);
        IntermediarioEntity intermediario = intermediarioOpt.orElseThrow(() ->
            new IllegalStateException("Nessun intermediario trovato per il dominio: " + codDominio));

        String codConnettore = intermediario.getCodConnettoreFr();
        if (codConnettore == null || codConnettore.isBlank()) {
            throw new IllegalStateException(
                "Connettore FDR non configurato per l'intermediario " + intermediario.getCodIntermediario()
                + " (dominio: " + codDominio + ")");
        }

        log.debug("Dominio {} -> Intermediario {} -> Connettore FDR: {}",
            codDominio, intermediario.getCodIntermediario(), codConnettore);
        return codConnettore;
    }

    /**
     * Gets or creates an OrganizationsApi instance for the given domain.
     * Uses a cache keyed by connector code to avoid creating duplicate instances
     * for domains sharing the same intermediary.
     */
    private OrganizationsApi getOrCreateApi(String codDominio) {
        String codConnettore = resolveConnectorCode(codDominio);
        return apiCache.computeIfAbsent(codConnettore, code -> {
            RestTemplate restTemplate = connettoreService.getRestTemplate(code);

            // Customize ObjectMapper for pagoPA date handling
            MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(fdrApiClientConfig.createPagoPAObjectMapper());
            restTemplate.getMessageConverters().removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
            restTemplate.getMessageConverters().add(0, converter);

            Connettore connettore = connettoreService.getConnettore(code);
            ApiClient apiClient = new ApiClient(restTemplate);
            apiClient.setBasePath(connettore.getUrl());

            log.info("Creata istanza OrganizationsApi per connettore {} (URL: {})", code, connettore.getUrl());
            return new OrganizationsApi(apiClient);
        });
    }

    /**
     * Returns the pagoPA base URL for the given domain (for GDE event tracking).
     * Delegates to ConnettoreService which has its own internal caching.
     */
    private String getBaseUrl(String codDominio) {
        String codConnettore = resolveConnectorCode(codDominio);
        return connettoreService.getConnettore(codConnettore).getUrl();
    }

    /**
     * Get all published flows for a domain with pagination
     */
    public List<FlowByPSP> getAllPublishedFlows(String organizationId, LocalDateTime publishedGt) throws RestClientException {

        log.debug("Recupero dei flussi pubblicati per l'organizzazione {} con publishedGt {}", organizationId, publishedGt);

        OffsetDateTime startTime = OffsetDateTime.now(ZoneOffset.UTC);
        List<FlowByPSP> allFlows = new ArrayList<>();
        Long currentPage = 1L;
        boolean hasMorePages = true;
        ResponseEntity<PaginatedFlowsResponse> lastResponseEntity = null;

        OffsetDateTime publishedGtOffset = publishedGt != null ? publishedGt.atZone(applicationZoneId).toOffsetDateTime() : null;
        try {
            while (hasMorePages) {
                PageFetchResult<PaginatedFlowsResponse> result = fetchFlowsPage(
                    organizationId, publishedGtOffset, currentPage);

                lastResponseEntity = result.responseEntity;
                PaginatedFlowsResponse response = extractFlowsResponse(result, organizationId, currentPage);

                if (response == null) {
                    hasMorePages = false;
                } else {
                    logInfoResponseOk(organizationId, response);
                    aggiungiFlussoRicevutoAllElenco(organizationId, allFlows, currentPage, response);
                    hasMorePages = hasMoreFlowPages(response);
                    currentPage++;
                }
            }

            log.info("Recuperati in totale {} flussi per l'organizzazione {}", allFlows.size(), organizationId);

            saveGetPublishedFlowsOk(organizationId, publishedGt, startTime, allFlows, lastResponseEntity);

            return allFlows;

        } catch (RestClientException e) {
            saveGetPublishedFlowsKo(organizationId, publishedGt, startTime, lastResponseEntity, e);
            throw e;
        }
    }

    /**
     * Fetches a single page of flows from the API.
     * Extracted to avoid nested try blocks (SonarQube java:S1141).
     */
    private PageFetchResult<PaginatedFlowsResponse> fetchFlowsPage(
            String organizationId, OffsetDateTime publishedGtOffset, Long currentPage) throws RestClientException {

        try {
            log.debug("Chiamata API per l'organizzazione {} pagina {}", organizationId, currentPage);

            ResponseEntity<PaginatedFlowsResponse> responseEntity =
                getOrCreateApi(organizationId).iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
                    organizationId,
                    null,           // flowDate
                    currentPage,    // page
                    null,           // pspId
                    publishedGtOffset,    // publishedGt
                    (long) batchProperties.getPageSize()  // size
                );

            return new PageFetchResult<>(responseEntity, true);

        } catch (org.springframework.web.client.ResourceAccessException e) {
            boolean shouldContinue = !gestioneRispostaVuota(organizationId, currentPage, e);
            return new PageFetchResult<>(null, shouldContinue);
        } catch (Exception e) {
            log.error("Errore nel recupero dei flussi per l'organizzazione {} alla pagina {}: {}",
                organizationId, currentPage, e.getMessage());
            log.error(e.getMessage(), e);
            throw new RestClientException("Fallito il recupero dei flussi per l'organizzazione " + organizationId, e);
        }
    }

	private boolean gestioneRispostaVuota(String organizationId, Long currentPage, org.springframework.web.client.ResourceAccessException e) {
		// Gestione risposta vuota (connessione chiusa) - normale quando non ci sono flussi disponibili
		if (e.getMessage() != null && e.getMessage().contains("closed")) {
		    log.info("Nessun flusso disponibile per l'organizzazione {} (risposta vuota)", organizationId);
		    return true;
		} else {
		    log.error("Errore I/O nel recupero dei flussi per l'organizzazione {} alla pagina {}: {}",
		        organizationId, currentPage, e.getMessage());
		    throw new RestClientException("Fallito il recupero dei flussi per l'organizzazione " + organizationId, e);
		}
	}

	private void aggiungiFlussoRicevutoAllElenco(String organizationId, List<FlowByPSP> allFlows, Long currentPage,
			PaginatedFlowsResponse response) {
		if (response.getData() != null && !response.getData().isEmpty()) {
		    allFlows.addAll(response.getData());
		    log.info("Recuperata pagina {} con {} flussi per l'organizzazione {}",
		        currentPage, response.getData().size(), organizationId);
		} else {
		    log.info("Pagina {} ha restituito dati vuoti per l'organizzazione {}", currentPage, organizationId);
		}
	}

	private PaginatedFlowsResponse extractFlowsResponse(PageFetchResult<PaginatedFlowsResponse> result,
			String organizationId, Long currentPage) {
		PaginatedFlowsResponse response = (result.success && result.responseEntity != null)
			? result.responseEntity.getBody() : null;
		if (response == null && result.success && result.responseEntity != null) {
			log.warn("Risposta con body vuoto per l'organizzazione {} alla pagina {}", organizationId, currentPage);
		}
		return response;
	}

	private boolean hasMoreFlowPages(PaginatedFlowsResponse response) {
		return response.getMetadata() != null
			&& response.getMetadata().getPageNumber() != null
			&& response.getMetadata().getTotPage() != null
			&& response.getMetadata().getPageNumber() < response.getMetadata().getTotPage();
	}

	private void logInfoResponseOk(String organizationId, PaginatedFlowsResponse response) {
		log.info("Chiamata API completata per l'organizzazione {}, risposta ricevuta: data={}, metadata={}",
		    organizationId,
		    response.getData() != null ? response.getData().size() + " flussi" : "null",
		    response.getMetadata());
	}

	private void saveGetPublishedFlowsKo(String organizationId, LocalDateTime publishedGt, OffsetDateTime startTime,
			ResponseEntity<PaginatedFlowsResponse> lastResponseEntity, RestClientException e) {
		OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);
		String urlEvento = gdeService.buildGetAllPublishedFlowsUrl(getBaseUrl(organizationId), organizationId, publishedGt != null ? publishedGt.toString() : "all");
		gdeService.saveGetPublishedFlowsKo(organizationId, null,
		    startTime, endTime, lastResponseEntity, e, urlEvento);
	}

	private void saveGetPublishedFlowsOk(String organizationId, LocalDateTime publishedGt, OffsetDateTime startTime,
			List<FlowByPSP> allFlows, ResponseEntity<PaginatedFlowsResponse> lastResponseEntity) {
		OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);
		String urlEvento = gdeService.buildGetAllPublishedFlowsUrl(getBaseUrl(organizationId), organizationId, publishedGt != null ? publishedGt.toString() : "all");
		gdeService.saveGetPublishedFlowsOk(organizationId, null,		    
		    startTime, endTime, allFlows.size(), lastResponseEntity, urlEvento);
	}

    /**
     * Get single published flow details
     */
    public SingleFlowResponse getSinglePublishedFlow(String organizationId, String fdr, Long revision, String pspId) throws RestClientException {

        log.debug("Recupero dettagli flusso per organization={}, fdr={}, revision={}, pspId={}", organizationId, fdr, revision, pspId);

        OffsetDateTime startTime = OffsetDateTime.now(ZoneOffset.UTC);
        ResponseEntity<SingleFlowResponse> responseEntity = null;
        try {
            responseEntity = getOrCreateApi(organizationId).iOrganizationsControllerGetSinglePublishedFlowWithHttpInfo(
                fdr,
                organizationId,
                pspId,
                revision
            );

            SingleFlowResponse response = responseEntity.getBody();
            log.info("Recuperati dettagli flusso per fdr={}: {}", fdr, response);

            // Send success event to GDE
            if (response != null) {
                OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);

                it.govpay.fdr.batch.entity.Fr frForEvent = it.govpay.fdr.batch.entity.Fr.builder()
                    .codFlusso(fdr)
                    .codPsp(pspId)
                    .codDominio(organizationId)
                    .revisione(revision)
                    .build();

                int paymentsCount = (response.getTotPayments() != null)
                    ? response.getTotPayments().intValue() : 0;

                gdeService.saveGetFlowDetailsOk(frForEvent, startTime, endTime, paymentsCount, responseEntity,
                    getBaseUrl(organizationId));
            }

            return response;

        } catch (Exception e) {
            log.error("Errore nel recupero dei dettagli del flusso per fdr={}: {}", fdr, e.getMessage());

            OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);

            it.govpay.fdr.batch.entity.Fr frForEvent = it.govpay.fdr.batch.entity.Fr.builder()
                .codFlusso(fdr)
                .codPsp(pspId)
                .codDominio(organizationId)
                .revisione(revision)
                .build();

            gdeService.saveGetFlowDetailsKo(frForEvent, startTime, endTime, responseEntity,
                e instanceof RestClientException restClientException ? restClientException : new RestClientException(e.getMessage(), e),
                getBaseUrl(organizationId));

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
                PageFetchResult<PaginatedPaymentsResponse> result = fetchPaymentsPage(
                    organizationId, fdr, revision, pspId, currentPage);

                lastResponseEntity = result.responseEntity;
                PaginatedPaymentsResponse response = lastResponseEntity != null ? lastResponseEntity.getBody() : null;

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
            }

            log.info("Recuperati in totale {} pagamenti per fdr {}", allPayments.size(), fdr);

            OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);

            it.govpay.fdr.batch.entity.Fr frForEvent = it.govpay.fdr.batch.entity.Fr.builder()
                .codFlusso(fdr)
                .codPsp(pspId)
                .codDominio(organizationId)
                .revisione(revision)
                .build();

            gdeService.saveGetPaymentsOk(frForEvent, startTime, endTime, allPayments.size(), lastResponseEntity,
                getBaseUrl(organizationId));

            return allPayments;

        } catch (RestClientException e) {
            OffsetDateTime endTime = OffsetDateTime.now(ZoneOffset.UTC);

            it.govpay.fdr.batch.entity.Fr frForEvent = it.govpay.fdr.batch.entity.Fr.builder()
                .codFlusso(fdr)
                .codPsp(pspId)
                .codDominio(organizationId)
                .revisione(revision)
                .build();

            gdeService.saveGetPaymentsKo(frForEvent, startTime, endTime, lastResponseEntity, e,
                getBaseUrl(organizationId));
            throw e;
        }
    }

    /**
     * Fetches a single page of payments from the API.
     * Extracted to avoid nested try blocks (SonarQube java:S1141).
     */
    private PageFetchResult<PaginatedPaymentsResponse> fetchPaymentsPage(
            String organizationId, String fdr, Long revision, String pspId, Long currentPage) throws RestClientException {

        try {
            ResponseEntity<PaginatedPaymentsResponse> responseEntity =
                getOrCreateApi(organizationId).iOrganizationsControllerGetPaymentsFromPublishedFlowWithHttpInfo(
                    fdr,
                    organizationId,
                    pspId,
                    revision,
                    currentPage,
                    (long) batchProperties.getPageSize()
                );

            return new PageFetchResult<>(responseEntity, true);

        } catch (Exception e) {
            log.error("Errore nel recupero dei pagamenti per fdr {} alla pagina {}: {}",
                fdr, currentPage, e.getMessage());
            throw new RestClientException("Fallito il recupero dei pagamenti per il flusso " + fdr, e);
        }
    }

    /**
     * Helper class to encapsulate the result of a page fetch operation.
     * Used to avoid nested try blocks.
     */
    private static class PageFetchResult<T> {
        final ResponseEntity<T> responseEntity;
        final boolean success;

        PageFetchResult(ResponseEntity<T> responseEntity, boolean success) {
            this.responseEntity = responseEntity;
            this.success = success;
        }
    }
}
