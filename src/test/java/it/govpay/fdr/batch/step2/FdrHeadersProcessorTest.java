package it.govpay.fdr.batch.step2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import it.govpay.fdr.batch.dto.DominioProcessingContext;
import it.govpay.fdr.batch.dto.FdrHeadersBatch;
import it.govpay.fdr.batch.service.FdrApiService;
import it.govpay.fdr.client.model.FlowByPSP;

/**
 * Unit tests for FdrHeadersProcessor
 */
@ExtendWith(MockitoExtension.class)
class FdrHeadersProcessorTest {

    @Mock
    private FdrApiService fdrApiService;
    
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Rome");

    private FdrHeadersProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new FdrHeadersProcessor(fdrApiService, ZONE_ID);
    }

    @Test
    @DisplayName("Should process domain with multiple FDR flows")
    void testProcessWithMultipleFlows() throws Exception {
        // Given: Domain with 3 flows
        String codDominio = "12345678901";
        LocalDateTime lastPubDate = LocalDateTime.of(2025, 1, 27, 10, 0, 0);
        DominioProcessingContext context = DominioProcessingContext.builder()
            .dominioId(1L)
            .codDominio(codDominio)
            .lastPublicationDate(lastPubDate)
            .build();

        List<FlowByPSP> flows = new ArrayList<>();
        flows.add(createFlowByPSP("FDR-001", "PSP001", 1L, "2025-01-27T10:30:00", "2025-01-27T11:00:00"));
        flows.add(createFlowByPSP("FDR-002", "PSP001", 1L, "2025-01-27T11:30:00", "2025-01-27T12:00:00"));
        flows.add(createFlowByPSP("FDR-003", "PSP002", 1L, "2025-01-27T12:30:00", "2025-01-27T13:00:00"));

        when(fdrApiService.getAllPublishedFlows(eq(codDominio), eq(lastPubDate))).thenReturn(flows);

        // When: Process
        FdrHeadersBatch result = processor.process(context);

        // Then: Should return batch with 3 headers
        assertThat(result).isNotNull();
        assertThat(result.getCodDominio()).isEqualTo(codDominio);
        assertThat(result.getHeaders()).hasSize(3);

        // Verify first header
        FdrHeadersBatch.FdrHeader header1 = result.getHeaders().get(0);
        assertThat(header1.getCodFlusso()).isEqualTo("FDR-001");
        assertThat(header1.getIdPsp()).isEqualTo("PSP001");
        assertThat(header1.getRevision()).isEqualTo(1L);
        assertThat(header1.getDataOraFlusso()).isNotNull();
        assertThat(header1.getDataOraPubblicazione()).isNotNull();
    }

    @Test
    @DisplayName("Should return null when no flows found")
    void testProcessWithNoFlows() throws Exception {
        // Given: Domain with no new flows
        DominioProcessingContext context = DominioProcessingContext.builder()
            .dominioId(1L)
            .codDominio("12345678901")
            .lastPublicationDate(LocalDateTime.now())
            .build();

        when(fdrApiService.getAllPublishedFlows(any(), any())).thenReturn(new ArrayList<>());

        // When: Process
        FdrHeadersBatch result = processor.process(context);

        // Then: Should return null (skip this domain)
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should throw RestClientException when API fails")
    void testProcessWithApiError() {
        // Given: Domain and API error
        DominioProcessingContext context = DominioProcessingContext.builder()
            .dominioId(1L)
            .codDominio("12345678901")
            .lastPublicationDate(LocalDateTime.now())
            .build();

        when(fdrApiService.getAllPublishedFlows(any(), any()))
            .thenThrow(new RestClientException("API Error"));

        // When/Then: Should throw exception
        assertThatThrownBy(() -> processor.process(context))
            .isInstanceOf(RestClientException.class)
            .hasMessageContaining("API Error");
    }

    @Test
    @DisplayName("Should handle flows with null dates")
    void testProcessWithNullDates() throws Exception {
        // Given: Flow with null dates
        DominioProcessingContext context = DominioProcessingContext.builder()
            .dominioId(1L)
            .codDominio("12345678901")
            .lastPublicationDate(LocalDateTime.now())
            .build();

        List<FlowByPSP> flows = new ArrayList<>();
        FlowByPSP flow = new FlowByPSP();
        flow.setFdr("FDR-001");
        flow.setPspId("PSP001");
        flow.setRevision(1L);
        flow.setFlowDate(null); // Null date
        flow.setPublished(null); // Null date
        flows.add(flow);

        when(fdrApiService.getAllPublishedFlows(any(), any())).thenReturn(flows);

        // When: Process
        FdrHeadersBatch result = processor.process(context);

        // Then: Should handle null dates gracefully
        assertThat(result).isNotNull();
        assertThat(result.getHeaders()).hasSize(1);
        assertThat(result.getHeaders().get(0).getDataOraFlusso()).isNull();
        assertThat(result.getHeaders().get(0).getDataOraPubblicazione()).isNull();
    }

    @Test
    @DisplayName("Should handle domain with null lastPublicationDate")
    void testProcessWithNullLastPublicationDate() throws Exception {
        // Given: Domain with null last publication date (first acquisition)
        DominioProcessingContext context = DominioProcessingContext.builder()
            .dominioId(1L)
            .codDominio("12345678901")
            .lastPublicationDate(null) // First time
            .build();

        List<FlowByPSP> flows = new ArrayList<>();
        flows.add(createFlowByPSP("FDR-001", "PSP001", 1L, "2025-01-27T10:30:00", "2025-01-27T11:00:00"));

        when(fdrApiService.getAllPublishedFlows(eq("12345678901"), eq(null))).thenReturn(flows);

        // When: Process
        FdrHeadersBatch result = processor.process(context);

        // Then: Should process successfully
        assertThat(result).isNotNull();
        assertThat(result.getHeaders()).hasSize(1);
    }

    private FlowByPSP createFlowByPSP(String fdr, String pspId, Long revision, String flowDate, String published) {
        FlowByPSP flow = new FlowByPSP();
        flow.setFdr(fdr);
        flow.setPspId(pspId);
        flow.setRevision(revision);
        flow.setFlowDate(OffsetDateTime.parse(flowDate + "+00:00"));
        flow.setPublished(OffsetDateTime.parse(published + "+00:00"));
        return flow;
    }
}
