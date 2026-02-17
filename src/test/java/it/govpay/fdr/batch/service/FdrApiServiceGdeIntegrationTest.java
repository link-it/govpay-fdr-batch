package it.govpay.fdr.batch.service;

import it.govpay.common.client.model.Connettore;
import it.govpay.common.client.service.ConnettoreService;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.fdr.batch.config.BatchProperties;
import it.govpay.fdr.batch.config.FdrApiClientConfig;
import it.govpay.fdr.batch.entity.Fr;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

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
    void testGetAllPublishedFlowsWithGdeTrackingFailure() throws Exception {
        // Given
        String organizationId = ORG_ID;
        LocalDateTime publishedGt = LocalDateTime.now().minusHours(1);

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(organizationId), isNull(), eq(1L), isNull(), any(OffsetDateTime.class), eq(100L)))
            .thenThrow(new HttpClientErrorException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Organization not found"));

        // When/Then
        assertThatThrownBy(() -> fdrApiService.getAllPublishedFlows(organizationId, publishedGt))
            .isInstanceOf(RestClientException.class)
            .hasMessageContaining("Fallito il recupero dei flussi");

        // Verify GDE error event was sent with built URL
        await().untilAsserted(() ->
            verify(gdeService).saveGetPublishedFlowsKo(
                eq(organizationId),
                isNull(),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                any(),
                any(RestClientException.class),
                anyString()
            )
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

        // Verify GDE event was sent with 0 payments and pagoPABaseUrl
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
}
