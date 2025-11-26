package it.govpay.fdr.batch.service;

import it.govpay.fdr.batch.config.PagoPAProperties;
import it.govpay.fdr.batch.gde.service.GdeService;
import it.govpay.fdr.client.api.OrganizationsApi;
import it.govpay.fdr.client.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
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

    @BeforeEach
    void setUp() {
        pagoPAProperties = new PagoPAProperties();
        pagoPAProperties.setBaseUrl("http://api.test.com");
        pagoPAProperties.setPageSize(100);
        pagoPAProperties.setDebugging(false);

        // Create service with mocked GDE
        fdrApiService = new FdrApiService(restTemplate, pagoPAProperties, gdeService);
    }

    @Test
    void testGetAllPublishedFlowsWithGdeTrackingSuccess() throws Exception {
        // Given
        String organizationId = "ORG123";
        Instant publishedGt = Instant.now().minusSeconds(3600);

        PaginatedFlowsResponse response = new PaginatedFlowsResponse();
        List<FlowByPSP> flows = new ArrayList<>();
        FlowByPSP flow = new FlowByPSP();
        flow.setFdr("FDR-001");
        flow.setPspId("PSP001");
        flows.add(flow);
        response.setData(flows);

        Metadata metadata = new Metadata();
        metadata.setPageNumber(1L);
        metadata.setTotPage(1L);
        response.setMetadata(metadata);

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlows(
            eq(organizationId), isNull(), eq(1L), isNull(), any(OffsetDateTime.class), eq(100L)))
            .thenReturn(response);

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
                contains("/organizations/" + organizationId + "/fdrs"),
                eq(1)
            )
        );
    }

    @Test
    void testGetAllPublishedFlowsWithGdeTrackingFailure() throws Exception {
        // Given
        String organizationId = "ORG123";
        Instant publishedGt = Instant.now().minusSeconds(3600);

        RestClientException exception = new HttpClientErrorException(
            org.springframework.http.HttpStatus.NOT_FOUND, "Organization not found");

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlows(
            eq(organizationId), isNull(), eq(1L), isNull(), any(OffsetDateTime.class), eq(100L)))
            .thenThrow(exception);

        // When/Then
        assertThatThrownBy(() -> fdrApiService.getAllPublishedFlows(organizationId, publishedGt))
            .isInstanceOf(RestClientException.class)
            .hasMessageContaining("Failed to fetch flows");

        // Verify GDE error event was sent
        await().untilAsserted(() ->
            verify(gdeService).saveGetPublishedFlowsKo(
                eq(organizationId),
                isNull(),
                eq(publishedGt.toString()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                contains("/organizations/" + organizationId + "/fdrs"),
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

        when(organizationsApi.iOrganizationsControllerGetSinglePublishedFlow(
            eq(fdr), eq(organizationId), eq(pspId), eq(revision)))
            .thenReturn(response);

        // When
        SingleFlowResponse result = fdrApiService.getSinglePublishedFlow(
            organizationId, fdr, revision, pspId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getFdr()).isEqualTo(fdr);

        // Verify GDE event was sent
        await().untilAsserted(() ->
            verify(gdeService).saveGetFlowDetailsOk(
                argThat(fr -> fr.getCodFlusso().equals(fdr)
                    && fr.getCodPsp().equals(pspId)
                    && fr.getCodDominio().equals(organizationId)
                    && fr.getRevisione().equals(revision)),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                contains("/organizations/" + organizationId + "/fdrs/" + fdr),
                eq(42)
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

        RestClientException exception = new RestClientException("Flow not found");

        when(organizationsApi.iOrganizationsControllerGetSinglePublishedFlow(
            eq(fdr), eq(organizationId), eq(pspId), eq(revision)))
            .thenThrow(exception);

        // When/Then
        assertThatThrownBy(() -> fdrApiService.getSinglePublishedFlow(
            organizationId, fdr, revision, pspId))
            .isInstanceOf(RestClientException.class)
            .hasMessageContaining("Failed to fetch flow details");

        // Verify GDE error event was sent
        await().untilAsserted(() ->
            verify(gdeService).saveGetFlowDetailsKo(
                argThat(fr -> fr.getCodFlusso().equals(fdr)
                    && fr.getCodPsp().equals(pspId)
                    && fr.getCodDominio().equals(organizationId)
                    && fr.getRevisione().equals(revision)),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                contains("/organizations/" + organizationId + "/fdrs/" + fdr),
                any(RestClientException.class)
            )
        );
    }

    @Test
    void testGetAllPublishedFlowsWithNullGdeService() throws Exception {
        // Given - service without GDE
        FdrApiService serviceWithoutGde = new FdrApiService(
            restTemplate, pagoPAProperties, null);

        String organizationId = "ORG123";

        PaginatedFlowsResponse response = new PaginatedFlowsResponse();
        response.setData(new ArrayList<>());

        Metadata metadata = new Metadata();
        metadata.setPageNumber(1L);
        metadata.setTotPage(1L);
        response.setMetadata(metadata);

        when(organizationsApi.iOrganizationsControllerGetAllPublishedFlows(
            eq(organizationId), isNull(), eq(1L), isNull(), isNull(), eq(100L)))
            .thenReturn(response);

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

        when(organizationsApi.iOrganizationsControllerGetSinglePublishedFlow(
            eq(fdr), eq(organizationId), eq(pspId), eq(revision)))
            .thenReturn(response);

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
                anyString(),
                eq(0) // Should default to 0 when totPayments is null
            )
        );
    }
}
