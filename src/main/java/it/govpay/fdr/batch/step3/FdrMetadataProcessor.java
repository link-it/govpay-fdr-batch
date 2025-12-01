package it.govpay.fdr.batch.step3;

import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.service.FdrApiService;
import it.govpay.fdr.client.model.SingleFlowResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Processor to fetch FDR metadata from pagoPA API
 */
@Component
@Slf4j
public class FdrMetadataProcessor implements ItemProcessor<FrTemp, FdrMetadataProcessor.FdrCompleteData> {

    private final FdrApiService fdrApiService;

    public FdrMetadataProcessor(FdrApiService fdrApiService) {
        this.fdrApiService = fdrApiService;
    }

    @Override
    public FdrCompleteData process(FrTemp frTemp) throws Exception {
        log.info("Processing FDR: domain={}, flow={}, revision={}, psp={}",
            frTemp.getCodDominio(), frTemp.getCodFlusso(), frTemp.getRevisione(), frTemp.getIdPsp());

        try {
            // Fetch FDR details
            SingleFlowResponse flowDetails = fdrApiService.getSinglePublishedFlow(
                frTemp.getCodDominio(),
                frTemp.getCodFlusso(),
                frTemp.getRevisione(),
                frTemp.getIdPsp()
            );

            return FdrCompleteData.builder()
                .frTempId(frTemp.getId())
                .codPsp(flowDetails.getSender() != null ? flowDetails.getSender().getPspId() : null)
                .codDominio(frTemp.getCodDominio())
                .codFlusso(frTemp.getCodFlusso())
                .iur(flowDetails.getRegulation())
                .dataOraFlusso(convertToInstant(flowDetails.getFdrDate()))
                .dataRegolamento(convertToInstant(flowDetails.getRegulationDate()))
                .numeroPagamenti(flowDetails.getTotPayments())
                .importoTotalePagamenti(flowDetails.getSumPayments())
                .codBicRiversamento(flowDetails.getBicCodePouringBank())
                .codPspMittente(flowDetails.getSender() != null ? flowDetails.getSender().getPspId() : null)
                .ragioneSocialePsp(flowDetails.getSender() != null ? flowDetails.getSender().getPspName() : null)
                .ragioneSocialeDominio(flowDetails.getReceiver() != null ? flowDetails.getReceiver().getOrganizationName() : null)
                .codIntermediarioPsp(flowDetails.getSender() != null ? flowDetails.getSender().getPspBrokerId() : null)
                .codCanale(flowDetails.getSender() != null ? flowDetails.getSender().getChannelId() : null)
                .dataOraPubblicazione(convertToInstant(flowDetails.getPublished()))
                .dataOraAggiornamento(convertToInstant(flowDetails.getUpdated()))
                .revisione(frTemp.getRevisione())
                .stato(flowDetails.getStatus() != null ? flowDetails.getStatus().name() : null)
                .build();

        } catch (RestClientException e) {
            log.error("Errore nell'elaborazione dell'FDR {}: {}", frTemp.getCodFlusso(), e.getMessage());
            throw e;
        }
    }

    private Instant convertToInstant(OffsetDateTime offsetDateTime) {
        if (offsetDateTime == null) {
            return null;
        }
        return offsetDateTime.toInstant();
    }

    private Instant convertToInstant(LocalDate localDate) {
        if (localDate == null) {
            return null;
        }
        return localDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * DTO containing complete FDR data with payments
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class FdrCompleteData {
        private Long frTempId;
        private String codPsp;
        private String codDominio;
        private String codFlusso;
        private String iur;
        private Instant dataOraFlusso;
        private Instant dataRegolamento;
        private Long numeroPagamenti;
        private Double importoTotalePagamenti;
        private String codBicRiversamento;
        private String codPspMittente;
        private String ragioneSocialePsp;
        private String ragioneSocialeDominio;
        private String codIntermediarioPsp;
        private String codCanale;
        private Instant dataOraPubblicazione;
        private Instant dataOraAggiornamento;
        private Long revisione;
        private String stato;
    }

}
