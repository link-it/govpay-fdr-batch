package it.govpay.fdr.batch.service;

import it.govpay.fdr.batch.config.PagoPAProperties;
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
    private RestTemplate restTemplate;

    @Mock
    private OrganizationsApi organizationsApi;

    @Mock
    private GdeService gdeService;

    private PagoPAProperties pagoPAProperties;
    private FdrApiService fdrApiService;
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Rome");

    @BeforeEach
    void setUp() {
        pagoPAProperties = new PagoPAProperties();
        pagoPAProperties.setBaseUrl("http://api.test.com");
        pagoPAProperties.setPageSize(100);
        pagoPAProperties.setDebugging(false);

        // Create service and inject mocked OrganizationsApi
        fdrApiService = new FdrApiService(restTemplate, pagoPAProperties, gdeService, ZONE_ID);
        ReflectionTestUtils.setField(fdrApiService, "organizationsApi", organizationsApi);
    }

    @Test
    void testGetAllPublishedFlowsWithGdeTrackingSuccess() throws Exception {
        // Given
        String organizationId = "ORG123";
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

        // Verify GDE event was sent
        await().untilAsserted(() ->
            verify(gdeService).saveGetPublishedFlowsOk(
                eq(organizationId),
                isNull(),
                eq(publishedGt.toString()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                eq(1),
                any()
            )
        );
    }

    @Test
    void testGetAllPublishedFlowsWithGdeTrackingFailure() throws Exception {
        // Given
        String organizationId = "ORG123";
        LocalDateTime publishedGt = LocalDateTime.now().minusHours(1);

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(organizationId), isNull(), eq(1L), isNull(), any(OffsetDateTime.class), eq(100L)))
            .thenThrow(new HttpClientErrorException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Organization not found"));

        // When/Then
        assertThatThrownBy(() -> fdrApiService.getAllPublishedFlows(organizationId, publishedGt))
            .isInstanceOf(RestClientException.class)
            .hasMessageContaining("Fallito il recupero dei flussi");

        // Verify GDE error event was sent
        await().untilAsserted(() ->
            verify(gdeService).saveGetPublishedFlowsKo(
                eq(organizationId),
                isNull(),
                eq(publishedGt.toString()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                any(),
                any(RestClientException.class)
            )
        );
    }

    @Test
    void testGetSinglePublishedFlowWithGdeTrackingSuccess() throws Exception {
        // Given
        String organizationId = "ORG123";
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

        // Verify GDE event was sent
        await().untilAsserted(() ->
            verify(gdeService).saveGetFlowDetailsOk(
                argThat((Fr fr) -> fr.getCodFlusso().equals(fdr)
                    && fr.getCodPsp().equals(pspId)
                    && fr.getCodDominio().equals(organizationId)
                    && fr.getRevisione().equals(revision)),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                eq(42),
                any()
            )
        );
    }

    @Test
    void testGetSinglePublishedFlowWithGdeTrackingFailure() throws Exception {
        // Given
        String organizationId = "ORG123";
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

        // Verify GDE error event was sent
        await().untilAsserted(() ->
            verify(gdeService).saveGetFlowDetailsKo(
                argThat((Fr fr) -> fr.getCodFlusso().equals(fdr)
                    && fr.getCodPsp().equals(pspId)
                    && fr.getCodDominio().equals(organizationId)
                    && fr.getRevisione().equals(revision)),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                any(),
                any(RestClientException.class)
            )
        );
    }

    @Test
    void testGetAllPublishedFlowsWithNullGdeService() throws Exception {
        // Given - service without GDE
        FdrApiService serviceWithoutGde = new FdrApiService(
            restTemplate, pagoPAProperties, null, ZONE_ID);
        ReflectionTestUtils.setField(serviceWithoutGde, "organizationsApi", organizationsApi);

        String organizationId = "ORG123";

        PaginatedFlowsResponse response = new PaginatedFlowsResponse();
        response.setData(new ArrayList<>());

        Metadata metadata = new Metadata();
        metadata.setPageNumber(1);
        metadata.setTotPage(1);
        response.setMetadata(metadata);

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlowsWithHttpInfo(
            eq(organizationId), isNull(), eq(1L), isNull(), isNull(), eq(100L)))
            .thenReturn(ResponseEntity.ok(response));

        // When
        List<FlowByPSP> result = serviceWithoutGde.getAllPublishedFlows(organizationId, null);

        // Then - should work without GDE
        assertThat(result).isEmpty();

        // GDE service should not be called (it's null)
        verifyNoInteractions(gdeService);
    }

    @Test
    void testGetSinglePublishedFlowWithResponseWithoutTotPayments() throws Exception {
        // Given
        String organizationId = "ORG123";
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

        // Verify GDE event was sent with 0 payments
        await().untilAsserted(() ->
            verify(gdeService).saveGetFlowDetailsOk(
                any(),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                eq(0), // Should default to 0 when totPayments is null
                any()
            )
        );
    }
}
