package it.govpay.fdr.batch.service;

import it.govpay.common.client.model.Connettore;
import it.govpay.common.client.service.ConnettoreService;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.fdr.batch.config.BatchProperties;
import it.govpay.fdr.batch.config.FdrApiClientConfig;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.exception.FdrFatalException;
import it.govpay.fdr.batch.gde.service.GdeService;
import it.govpay.fdr.client.api.OrganizationsApi;
import it.govpay.fdr.client.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for FdrApiService with GDE event tracking.
 */
@ExtendWith(MockitoExtension.class)
class FdrApiServiceGdeIntegrationTest {

    @Mock
    private OrganizationsApi organizationsApi;

    @Mock
    private GdeService gdeService;

    @Mock
    private ConnettoreService connettoreService;

    @Mock
    private IntermediarioRepository intermediarioRepository;

    @Mock
    private FdrApiClientConfig fdrApiClientConfig;

    private BatchProperties batchProperties;
    private FdrApiService fdrApiService;
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Rome");
    private static final String ORG_ID = "ORG123";
    private static final String COD_CONNETTORE = "PAGOPA_FDR";
    private static final String BASE_URL = "http://api.test.com";

    @BeforeEach
    void setUp() {
        batchProperties = new BatchProperties();
        batchProperties.setPageSize(100);

        // Mock intermediario resolution
        IntermediarioEntity intermediario = IntermediarioEntity.builder()
            .codIntermediario("INT001")
            .codConnettoreFr(COD_CONNETTORE)
            .abilitato(true)
            .build();
        lenient().when(intermediarioRepository.findByCodDominio(ORG_ID)).thenReturn(Optional.of(intermediario));

        // Mock connettore
        Connettore connettore = new Connettore();
        connettore.setUrl(BASE_URL);
        lenient().when(connettoreService.getConnettore(COD_CONNETTORE)).thenReturn(connettore);
        lenient().when(connettoreService.getRestTemplate(COD_CONNETTORE)).thenReturn(new RestTemplate());

        // Mock buildGetAllPublishedFlowsUrl (gdeService is a mock)
        lenient().when(gdeService.buildGetAllPublishedFlowsUrl(anyString(), anyString(), anyString()))
            .thenAnswer(inv -> inv.getArgument(0) + "/organizations/" + inv.getArgument(1) + "/fdrs?publishedGt=" + inv.getArgument(2));

        // Create service and inject mocked OrganizationsApi via cache
        fdrApiService = new FdrApiService(batchProperties, connettoreService, intermediarioRepository,
            gdeService, ZONE_ID, fdrApiClientConfig);

        // Inject mocked OrganizationsApi into the cache
        ConcurrentHashMap<String, OrganizationsApi> apiCache = new ConcurrentHashMap<>();
        apiCache.put(COD_CONNETTORE, organizationsApi);
        ReflectionTestUtils.setField(fdrApiService, "apiCache", apiCache);
    }

    @Test
    void testGetAllPublishedFlowsWithGdeTrackingSuccess() throws Exception {
        // Given
        String organizationId = ORG_ID;
        LocalDateTime publishedGt = LocalDateTime.now().minusHours(1);

        PaginatedFlowsResponse response = new PaginatedFlowsResponse();
        List<FlowByPSP> flows = new ArrayList<>();
        FlowByPSP flow = new FlowByPSP();
        flow.setFdr("FDR-001");
        flow.setPspId("PSP001");
        flows.add(flow);
        response.setData(flows);

        Metadata metadata = new Metadata();
        metadata.setPageNumber(1);
        metadata.setTotPage(1);
        response.setMetadata(metadata);

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(organizationId), isNull(), eq(1L), isNull(), any(OffsetDateTime.class), eq(100L)))
            .thenReturn(ResponseEntity.ok(response));

        // When
        List<FlowByPSP> result = fdrApiService.getAllPublishedFlows(organizationId, publishedGt);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFdr()).isEqualTo("FDR-001");

        // Verify GDE event was sent with built URL
        await().untilAsserted(() ->
            verify(gdeService).saveGetPublishedFlowsOk(
                eq(organizationId),
                isNull(),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                eq(1),
                any(),
                anyString()
            )
        );
    }

    @Test
    void testGetAllPublishedFlows404ReturnsEmptyListAsSuccess() throws Exception {
        // Given - 404 indica che non ci sono flussi per l'organizzazione
        String organizationId = ORG_ID;
        LocalDateTime publishedGt = LocalDateTime.now().minusHours(1);

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(organizationId), isNull(), eq(1L), isNull(), any(OffsetDateTime.class), eq(100L)))
            .thenThrow(new HttpClientErrorException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Organization not found"));

        // When - 404 viene trattato come successo (nessun flusso disponibile)
        List<FlowByPSP> result = fdrApiService.getAllPublishedFlows(organizationId, publishedGt);

        // Then - lista vuota, nessuna eccezione
        assertThat(result).isEmpty();

        // Verify GDE OK event was sent (0 flows)
        verify(gdeService).saveGetPublishedFlowsOk(
            eq(organizationId),
            isNull(),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class),
            eq(0),
            any(),
            anyString()
        );
        // Verify NO KO event was sent
        verify(gdeService, never()).saveGetPublishedFlowsKo(
            anyString(), any(), any(), any(), any(), any(RestClientException.class), anyString());
    }

    @Test
    void testGetAllPublishedFlows404WithEmptyBody() throws Exception {
        // Given - 404 con body vuoto (come in produzione: "[no body]")
        String organizationId = ORG_ID;

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(organizationId), isNull(), eq(1L), isNull(), any(OffsetDateTime.class), eq(100L)))
            .thenThrow(HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "Not Found",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        // When
        List<FlowByPSP> result = fdrApiService.getAllPublishedFlows(organizationId, LocalDateTime.now());

        // Then - lista vuota, nessuna eccezione
        assertThat(result).isEmpty();
    }

    @Test
    void testGetAllPublishedFlows401ThrowsFdrFatalException() throws Exception {
        // Given - 401 Unauthorized
        String organizationId = ORG_ID;
        LocalDateTime publishedGt = LocalDateTime.now().minusHours(1);

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(organizationId), isNull(), eq(1L), isNull(), any(OffsetDateTime.class), eq(100L)))
            .thenThrow(new HttpClientErrorException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized"));

        // When/Then - deve lanciare FdrFatalException (non ritentabile)
        assertThatThrownBy(() -> fdrApiService.getAllPublishedFlows(organizationId, publishedGt))
            .isInstanceOf(FdrFatalException.class)
            .hasMessageContaining("autenticazione/autorizzazione")
            .hasMessageContaining("401")
            .hasCauseInstanceOf(HttpClientErrorException.class);

        // Verify GDE KO event was sent con l'eccezione originale
        verify(gdeService).saveGetPublishedFlowsKo(
            eq(organizationId),
            isNull(),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class),
            any(),
            any(HttpClientErrorException.class),
            anyString()
        );
    }

    @Test
    void testGetAllPublishedFlows403ThrowsFdrFatalException() throws Exception {
        // Given - 403 Forbidden
        String organizationId = ORG_ID;
        LocalDateTime publishedGt = LocalDateTime.now().minusHours(1);

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(organizationId), isNull(), eq(1L), isNull(), any(OffsetDateTime.class), eq(100L)))
            .thenThrow(new HttpClientErrorException(
                org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden"));

        // When/Then - deve lanciare FdrFatalException (non ritentabile)
        assertThatThrownBy(() -> fdrApiService.getAllPublishedFlows(organizationId, publishedGt))
            .isInstanceOf(FdrFatalException.class)
            .hasMessageContaining("autenticazione/autorizzazione")
            .hasMessageContaining("403")
            .hasCauseInstanceOf(HttpClientErrorException.class);

        // Verify GDE KO event was sent
        verify(gdeService).saveGetPublishedFlowsKo(
            eq(organizationId),
            isNull(),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class),
            any(),
            any(HttpClientErrorException.class),
            anyString()
        );
    }

    @Test
    void testGetAllPublishedFlows401GdeSavesResponseBody() throws Exception {
        // Given - 401 con body JSON
        String organizationId = ORG_ID;
        String responseBody = "{\"httpStatusCode\":401,\"httpStatusDescription\":\"Unauthorized\",\"appErrorCode\":\"AUTH-001\"}";

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(organizationId), isNull(), eq(1L), isNull(), any(OffsetDateTime.class), eq(100L)))
            .thenThrow(HttpClientErrorException.create(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized",
                org.springframework.http.HttpHeaders.EMPTY,
                responseBody.getBytes(), null));

        // When/Then
        assertThatThrownBy(() -> fdrApiService.getAllPublishedFlows(organizationId, LocalDateTime.now()))
            .isInstanceOf(FdrFatalException.class);

        // Verify GDE KO event carries the HttpClientErrorException (con il body della response)
        verify(gdeService).saveGetPublishedFlowsKo(
            eq(organizationId),
            isNull(),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class),
            any(),
            argThat(ex -> ex instanceof HttpClientErrorException
                && ((HttpClientErrorException) ex).getResponseBodyAsString().contains("AUTH-001")),
            anyString()
        );
    }

    @Test
    void testGetSinglePublishedFlowWithGdeTrackingSuccess() throws Exception {
        // Given
        String organizationId = ORG_ID;
        String fdr = "FDR-001";
        Long revision = 1L;
        String pspId = "PSP001";

        SingleFlowResponse response = new SingleFlowResponse();
        response.setFdr(fdr);
        response.setRevision(revision);
        response.setTotPayments(42L);

        when(organizationsApi.iOrganizationsControllerGetSinglePublishedFlowWithHttpInfo(
            eq(fdr), eq(organizationId), eq(pspId), eq(revision)))
            .thenReturn(ResponseEntity.ok(response));

        // When
        SingleFlowResponse result = fdrApiService.getSinglePublishedFlow(
            organizationId, fdr, revision, pspId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getFdr()).isEqualTo(fdr);

        // Verify GDE event was sent with pagoPABaseUrl
        await().untilAsserted(() ->
            verify(gdeService).saveGetFlowDetailsOk(
                argThat((Fr fr) -> fr.getCodFlusso().equals(fdr)
                    && fr.getCodPsp().equals(pspId)
                    && fr.getCodDominio().equals(organizationId)
                    && fr.getRevisione().equals(revision)),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                eq(42),
                any(),
                eq(BASE_URL)
            )
        );
    }

    @Test
    void testGetSinglePublishedFlowWithGdeTrackingFailure() throws Exception {
        // Given
        String organizationId = ORG_ID;
        String fdr = "FDR-001";
        Long revision = 1L;
        String pspId = "PSP001";

        when(organizationsApi.iOrganizationsControllerGetSinglePublishedFlowWithHttpInfo(
            eq(fdr), eq(organizationId), eq(pspId), eq(revision)))
            .thenThrow(new RestClientException("Flow not found"));

        // When/Then
        assertThatThrownBy(() -> fdrApiService.getSinglePublishedFlow(
            organizationId, fdr, revision, pspId))
            .isInstanceOf(RestClientException.class)
            .hasMessageContaining("Fallito il recupero dei dettagli del flusso");

        // Verify GDE error event was sent with pagoPABaseUrl
        await().untilAsserted(() ->
            verify(gdeService).saveGetFlowDetailsKo(
                argThat((Fr fr) -> fr.getCodFlusso().equals(fdr)
                    && fr.getCodPsp().equals(pspId)
                    && fr.getCodDominio().equals(organizationId)
                    && fr.getRevisione().equals(revision)),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                any(),
                any(RestClientException.class),
                eq(BASE_URL)
            )
        );
    }

    @Test
    void testGetSinglePublishedFlowWithResponseWithoutTotPayments() throws Exception {
        // Given
        String organizationId = ORG_ID;
        String fdr = "FDR-001";
        Long revision = 1L;
        String pspId = "PSP001";

        SingleFlowResponse response = new SingleFlowResponse();
        response.setFdr(fdr);
        response.setRevision(revision);
        response.setTotPayments(null); // No totPayments

        when(organizationsApi.iOrganizationsControllerGetSinglePublishedFlowWithHttpInfo(
            eq(fdr), eq(organizationId), eq(pspId), eq(revision)))
            .thenReturn(ResponseEntity.ok(response));

        // When
        SingleFlowResponse result = fdrApiService.getSinglePublishedFlow(
            organizationId, fdr, revision, pspId);

        // Then
        assertThat(result).isNotNull();

        // Verify GDE event was sent with 0 payments, pagoPABaseUrl
        await().untilAsserted(() ->
            verify(gdeService).saveGetFlowDetailsOk(
                any(),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                eq(0), // Should default to 0 when totPayments is null
                any(),
                eq(BASE_URL)
            )
        );
    }

    // ==================== getPaymentsFromPublishedFlow tests ====================

    @Test
    void testGetPaymentsSuccess() throws Exception {
        // Given
        String fdr = "FDR-001";
        Long revision = 1L;
        String pspId = "PSP001";

        PaginatedPaymentsResponse response = new PaginatedPaymentsResponse();
        List<Payment> payments = new ArrayList<>();
        Payment payment = new Payment();
        payment.setIndex(1L);
        payments.add(payment);
        response.setData(payments);

        Metadata metadata = new Metadata();
        metadata.setPageNumber(1);
        metadata.setTotPage(1);
        response.setMetadata(metadata);

        when(organizationsApi.iOrganizationsControllerGetPaymentsFromPublishedFlowWithHttpInfo(
            eq(fdr), eq(ORG_ID), eq(pspId), eq(revision), eq(1L), eq(100L)))
            .thenReturn(ResponseEntity.ok(response));

        // When
        List<Payment> result = fdrApiService.getPaymentsFromPublishedFlow(ORG_ID, fdr, revision, pspId);

        // Then
        assertThat(result).hasSize(1);
        verify(gdeService).saveGetPaymentsOk(any(Fr.class), any(), any(), eq(1), any(), eq(BASE_URL));
    }

    @Test
    void testGetPaymentsFailure() throws Exception {
        // Given
        String fdr = "FDR-001";
        Long revision = 1L;
        String pspId = "PSP001";

        when(organizationsApi.iOrganizationsControllerGetPaymentsFromPublishedFlowWithHttpInfo(
            eq(fdr), eq(ORG_ID), eq(pspId), eq(revision), eq(1L), eq(100L)))
            .thenThrow(new RestClientException("Payments error"));

        // When/Then
        assertThatThrownBy(() -> fdrApiService.getPaymentsFromPublishedFlow(ORG_ID, fdr, revision, pspId))
            .isInstanceOf(RestClientException.class);

        verify(gdeService).saveGetPaymentsKo(any(Fr.class), any(), any(), any(), any(RestClientException.class), eq(BASE_URL));
    }

    @Test
    void testGetPaymentsMultiPage() throws Exception {
        // Given
        String fdr = "FDR-001";
        Long revision = 1L;
        String pspId = "PSP001";

        // Page 1
        PaginatedPaymentsResponse response1 = new PaginatedPaymentsResponse();
        List<Payment> payments1 = new ArrayList<>();
        Payment p1 = new Payment();
        p1.setIndex(1L);
        payments1.add(p1);
        response1.setData(payments1);
        Metadata meta1 = new Metadata();
        meta1.setPageNumber(1);
        meta1.setTotPage(2);
        response1.setMetadata(meta1);

        // Page 2
        PaginatedPaymentsResponse response2 = new PaginatedPaymentsResponse();
        List<Payment> payments2 = new ArrayList<>();
        Payment p2 = new Payment();
        p2.setIndex(2L);
        payments2.add(p2);
        response2.setData(payments2);
        Metadata meta2 = new Metadata();
        meta2.setPageNumber(2);
        meta2.setTotPage(2);
        response2.setMetadata(meta2);

        when(organizationsApi.iOrganizationsControllerGetPaymentsFromPublishedFlowWithHttpInfo(
            eq(fdr), eq(ORG_ID), eq(pspId), eq(revision), eq(1L), eq(100L)))
            .thenReturn(ResponseEntity.ok(response1));
        when(organizationsApi.iOrganizationsControllerGetPaymentsFromPublishedFlowWithHttpInfo(
            eq(fdr), eq(ORG_ID), eq(pspId), eq(revision), eq(2L), eq(100L)))
            .thenReturn(ResponseEntity.ok(response2));

        // When
        List<Payment> result = fdrApiService.getPaymentsFromPublishedFlow(ORG_ID, fdr, revision, pspId);

        // Then
        assertThat(result).hasSize(2);
    }

    // ==================== getAllPublishedFlows edge cases ====================

    @Test
    void testGetAllPublishedFlowsWithNullPublishedGt() throws Exception {
        PaginatedFlowsResponse response = new PaginatedFlowsResponse();
        response.setData(new ArrayList<>());
        Metadata metadata = new Metadata();
        metadata.setPageNumber(1);
        metadata.setTotPage(1);
        response.setMetadata(metadata);

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(ORG_ID), isNull(), eq(1L), isNull(), isNull(), eq(100L)))
            .thenReturn(ResponseEntity.ok(response));

        // When - publishedGt is null
        List<FlowByPSP> result = fdrApiService.getAllPublishedFlows(ORG_ID, null);

        // Then
        assertThat(result).isEmpty();
        verify(gdeService).saveGetPublishedFlowsOk(eq(ORG_ID), isNull(), any(), any(), eq(0), any(), anyString());
    }

    @Test
    void testGetAllPublishedFlowsMultiPage() throws Exception {
        // Page 1
        PaginatedFlowsResponse response1 = new PaginatedFlowsResponse();
        List<FlowByPSP> flows1 = new ArrayList<>();
        FlowByPSP f1 = new FlowByPSP();
        f1.setFdr("FDR-001");
        flows1.add(f1);
        response1.setData(flows1);
        Metadata meta1 = new Metadata();
        meta1.setPageNumber(1);
        meta1.setTotPage(2);
        response1.setMetadata(meta1);

        // Page 2
        PaginatedFlowsResponse response2 = new PaginatedFlowsResponse();
        List<FlowByPSP> flows2 = new ArrayList<>();
        FlowByPSP f2 = new FlowByPSP();
        f2.setFdr("FDR-002");
        flows2.add(f2);
        response2.setData(flows2);
        Metadata meta2 = new Metadata();
        meta2.setPageNumber(2);
        meta2.setTotPage(2);
        response2.setMetadata(meta2);

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(ORG_ID), isNull(), eq(1L), isNull(), any(), eq(100L)))
            .thenReturn(ResponseEntity.ok(response1));
        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(ORG_ID), isNull(), eq(2L), isNull(), any(), eq(100L)))
            .thenReturn(ResponseEntity.ok(response2));

        // When
        List<FlowByPSP> result = fdrApiService.getAllPublishedFlows(ORG_ID, LocalDateTime.now());

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getFdr()).isEqualTo("FDR-001");
        assertThat(result.get(1).getFdr()).isEqualTo("FDR-002");
    }

    @Test
    void testGetAllPublishedFlowsWithNullMetadata() throws Exception {
        PaginatedFlowsResponse response = new PaginatedFlowsResponse();
        List<FlowByPSP> flows = new ArrayList<>();
        FlowByPSP f = new FlowByPSP();
        f.setFdr("FDR-001");
        flows.add(f);
        response.setData(flows);
        response.setMetadata(null); // no metadata -> should stop pagination

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(ORG_ID), isNull(), eq(1L), isNull(), any(), eq(100L)))
            .thenReturn(ResponseEntity.ok(response));

        // When
        List<FlowByPSP> result = fdrApiService.getAllPublishedFlows(ORG_ID, LocalDateTime.now());

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    void testGetAllPublishedFlowsWithEmptyData() throws Exception {
        PaginatedFlowsResponse response = new PaginatedFlowsResponse();
        response.setData(new ArrayList<>()); // empty data list
        Metadata metadata = new Metadata();
        metadata.setPageNumber(1);
        metadata.setTotPage(1);
        response.setMetadata(metadata);

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(ORG_ID), isNull(), eq(1L), isNull(), any(), eq(100L)))
            .thenReturn(ResponseEntity.ok(response));

        List<FlowByPSP> result = fdrApiService.getAllPublishedFlows(ORG_ID, LocalDateTime.now());

        assertThat(result).isEmpty();
    }

    @Test
    void testGetAllPublishedFlowsResourceAccessExceptionConnectionClosed() throws Exception {
        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(ORG_ID), isNull(), eq(1L), isNull(), any(), eq(100L)))
            .thenThrow(new ResourceAccessException("I/O error: connection closed"));

        // When - connection closed is treated as "no flows available", not an error
        List<FlowByPSP> result = fdrApiService.getAllPublishedFlows(ORG_ID, LocalDateTime.now());

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void testGetAllPublishedFlowsResourceAccessExceptionOtherIoError() throws Exception {
        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(ORG_ID), isNull(), eq(1L), isNull(), any(), eq(100L)))
            .thenThrow(new ResourceAccessException("I/O error: Connection refused", new IOException("Connection refused")));

        assertThatThrownBy(() -> fdrApiService.getAllPublishedFlows(ORG_ID, LocalDateTime.now()))
            .isInstanceOf(RestClientException.class);
    }

    // ==================== Connector resolution error tests ====================

    @Test
    void testResolveConnectorCodeNoIntermediarioFound() {
        String unknownOrg = "UNKNOWN_ORG";
        when(intermediarioRepository.findByCodDominio(unknownOrg)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fdrApiService.getAllPublishedFlows(unknownOrg, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Nessun intermediario trovato per il dominio");
    }

    @Test
    void testResolveConnectorCodeBlankConnettore() {
        String orgWithBlank = "ORG_BLANK";
        IntermediarioEntity intermediario = IntermediarioEntity.builder()
            .codIntermediario("INT002")
            .codConnettoreFr("")  // blank
            .abilitato(true)
            .build();
        when(intermediarioRepository.findByCodDominio(orgWithBlank)).thenReturn(Optional.of(intermediario));

        assertThatThrownBy(() -> fdrApiService.getAllPublishedFlows(orgWithBlank, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Connettore FDR non configurato");
    }

    @Test
    void testResolveConnectorCodeNullConnettore() {
        String orgWithNull = "ORG_NULL";
        IntermediarioEntity intermediario = IntermediarioEntity.builder()
            .codIntermediario("INT003")
            .codConnettoreFr(null)
            .abilitato(true)
            .build();
        when(intermediarioRepository.findByCodDominio(orgWithNull)).thenReturn(Optional.of(intermediario));

        assertThatThrownBy(() -> fdrApiService.getAllPublishedFlows(orgWithNull, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Connettore FDR non configurato");
    }

    // ==================== getSinglePublishedFlow edge cases ====================

    @Test
    void testGetSinglePublishedFlowWithNonRestClientException() throws Exception {
        String fdr = "FDR-001";
        Long revision = 1L;
        String pspId = "PSP001";

        when(organizationsApi.iOrganizationsControllerGetSinglePublishedFlowWithHttpInfo(
            eq(fdr), eq(ORG_ID), eq(pspId), eq(revision)))
            .thenThrow(new RuntimeException("Unexpected error"));

        assertThatThrownBy(() -> fdrApiService.getSinglePublishedFlow(ORG_ID, fdr, revision, pspId))
            .isInstanceOf(RestClientException.class)
            .hasMessageContaining("Fallito il recupero dei dettagli del flusso");

        // Verify GDE ko event wraps the exception as RestClientException
        verify(gdeService).saveGetFlowDetailsKo(
            any(Fr.class), any(), any(), any(), any(RestClientException.class), eq(BASE_URL));
    }

    @Test
    void testGetPaymentsWithNullResponseData() throws Exception {
        String fdr = "FDR-001";
        Long revision = 1L;
        String pspId = "PSP001";

        PaginatedPaymentsResponse response = new PaginatedPaymentsResponse();
        response.setData(null); // null data
        Metadata metadata = new Metadata();
        metadata.setPageNumber(1);
        metadata.setTotPage(1);
        response.setMetadata(metadata);

        when(organizationsApi.iOrganizationsControllerGetPaymentsFromPublishedFlowWithHttpInfo(
            eq(fdr), eq(ORG_ID), eq(pspId), eq(revision), eq(1L), eq(100L)))
            .thenReturn(ResponseEntity.ok(response));

        List<Payment> result = fdrApiService.getPaymentsFromPublishedFlow(ORG_ID, fdr, revision, pspId);

        assertThat(result).isEmpty();
    }

    @Test
    void testGetPaymentsWithNullMetadata() throws Exception {
        String fdr = "FDR-001";
        Long revision = 1L;
        String pspId = "PSP001";

        PaginatedPaymentsResponse response = new PaginatedPaymentsResponse();
        List<Payment> payments = new ArrayList<>();
        Payment p = new Payment();
        p.setIndex(1L);
        payments.add(p);
        response.setData(payments);
        response.setMetadata(null); // null metadata -> stop

        when(organizationsApi.iOrganizationsControllerGetPaymentsFromPublishedFlowWithHttpInfo(
            eq(fdr), eq(ORG_ID), eq(pspId), eq(revision), eq(1L), eq(100L)))
            .thenReturn(ResponseEntity.ok(response));

        List<Payment> result = fdrApiService.getPaymentsFromPublishedFlow(ORG_ID, fdr, revision, pspId);

        assertThat(result).hasSize(1);
    }
}
