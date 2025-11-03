package it.govpay.fdr.batch.step2;

import it.govpay.fdr.batch.dto.DominioProcessingContext;
import it.govpay.fdr.batch.dto.FdrHeadersBatch;
import it.govpay.fdr.batch.service.FdrApiService;
import it.govpay.fdr.client.model.FlowByPSP;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Processor to fetch FDR headers from pagoPA API for each domain
 */
@Component
@Slf4j
public class FdrHeadersProcessor implements ItemProcessor<DominioProcessingContext, FdrHeadersBatch> {

    private final FdrApiService fdrApiService;

    public FdrHeadersProcessor(FdrApiService fdrApiService) {
        this.fdrApiService = fdrApiService;
    }

    @Override
    public FdrHeadersBatch process(DominioProcessingContext context) throws Exception {
        log.info("Processing domain: {} with last publication date: {}",
            context.getCodDominio(), context.getLastPublicationDate());

        try {
            // Fetch published flows for this domain
            List<FlowByPSP> flows = fdrApiService.getAllPublishedFlows(
                context.getCodDominio(),
                context.getLastPublicationDate()
            );

            if (flows.isEmpty()) {
                log.info("No new flows found for domain {}", context.getCodDominio());
                return null; // Skip this domain
            }

            // Convert to internal DTO
            List<FdrHeadersBatch.FdrHeader> headers = new ArrayList<>();
            for (FlowByPSP flow : flows) {
                headers.add(FdrHeadersBatch.FdrHeader.builder()
                    .codFlusso(flow.getFdr())
                    .codPsp(flow.getPspId())
                    .revision(flow.getRevision())
                    .dataFlusso(convertToInstant(flow.getFlowDate()))
                    .dataPubblicazione(convertToInstant(flow.getPublished()))
                    .build());
            }

            log.info("Found {} FDR headers for domain {}", headers.size(), context.getCodDominio());

            return FdrHeadersBatch.builder()
                .codDominio(context.getCodDominio())
                .headers(headers)
                .build();

        } catch (RestClientException e) {
            log.error("Error processing domain {}: {}", context.getCodDominio(), e.getMessage());
            // Allow retry mechanism to handle this
            throw e;
        }
    }

    private Instant convertToInstant(OffsetDateTime offsetDateTime) {
        if (offsetDateTime == null) {
            return null;
        }
        return offsetDateTime.toInstant();
    }
}
