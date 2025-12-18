package it.govpay.fdr.batch.step2;

import it.govpay.fdr.batch.dto.DominioProcessingContext;
import it.govpay.fdr.batch.dto.FdrHeadersBatch;
import it.govpay.fdr.batch.service.FdrApiService;
import it.govpay.fdr.client.model.FlowByPSP;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Processor to fetch FDR headers from pagoPA API for each domain
 */
@Component
@Slf4j
public class FdrHeadersProcessor implements ItemProcessor<DominioProcessingContext, FdrHeadersBatch> {

    private final FdrApiService fdrApiService;
    private final ZoneId applicationZoneId;

    public FdrHeadersProcessor(FdrApiService fdrApiService, ZoneId applicationZoneId) {
        this.fdrApiService = fdrApiService;
        this.applicationZoneId = applicationZoneId;
    }

    private FdrHeadersBatch.FdrHeader flowConverter(FlowByPSP flow) {
    	return FdrHeadersBatch.FdrHeader.builder()
                .codFlusso(flow.getFdr())
                .idPsp(flow.getPspId())
                .revision(flow.getRevision())
                .dataOraFlusso(convertToLocalDateTime(flow.getFlowDate()))
                .dataOraPubblicazione(convertToLocalDateTime(flow.getPublished()))
                .build();
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
                log.info("Nessun nuovo flusso trovato per il dominio {}", context.getCodDominio());
                return null; // Skip this domain
            }

            // Convert to internal DTO
            List<FdrHeadersBatch.FdrHeader> headers = flows.stream().map(this::flowConverter).toList();
            log.info("Found {} FDR headers for domain {}", headers.size(), context.getCodDominio());

            return FdrHeadersBatch.builder()
                .codDominio(context.getCodDominio())
                .headers(headers)
                .build();

        } catch (RestClientException e) {
            log.error("Errore nell'elaborazione del dominio {}: {}", context.getCodDominio(), e.getMessage());
            // Allow retry mechanism to handle this
            throw e;
        }
    }

    private LocalDateTime convertToLocalDateTime(OffsetDateTime offsetDateTime) {
        if (offsetDateTime == null) {
            return null;
        }
        return offsetDateTime.atZoneSameInstant(applicationZoneId).toLocalDateTime();
    }
}
