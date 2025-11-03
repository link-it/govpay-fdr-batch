package it.govpay.fdr.batch.step3;

import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.service.FdrApiService;
import it.govpay.fdr.client.model.Payment;
import it.govpay.fdr.client.model.SingleFlowResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Processor to fetch FDR details and payments from pagoPA API
 */
@Component
@Slf4j
public class FdrPaymentsProcessor implements ItemProcessor<FrTemp, FdrPaymentsProcessor.FdrCompleteData> {

    private final FdrApiService fdrApiService;

    public FdrPaymentsProcessor(FdrApiService fdrApiService) {
        this.fdrApiService = fdrApiService;
    }

    @Override
    public FdrCompleteData process(FrTemp frTemp) throws Exception {
        log.info("Processing FDR: domain={}, flow={}, revision={}, psp={}",
            frTemp.getCodDominio(), frTemp.getCodFlusso(), frTemp.getRevision(), frTemp.getCodPsp());

        try {
            // Fetch FDR details
            SingleFlowResponse flowDetails = fdrApiService.getSinglePublishedFlow(
                frTemp.getCodDominio(),
                frTemp.getCodFlusso(),
                frTemp.getRevision(),
                frTemp.getCodPsp()
            );

            // Fetch payments
            List<Payment> payments = fdrApiService.getPaymentsFromPublishedFlow(
                frTemp.getCodDominio(),
                frTemp.getCodFlusso(),
                frTemp.getRevision(),
                frTemp.getCodPsp()
            );

            log.info("Retrieved {} payments for FDR {}", payments.size(), frTemp.getCodFlusso());

            // Convert to internal model
            List<PaymentData> paymentDataList = payments.stream()
                .map(this::convertPayment)
                .collect(Collectors.toList());

            return FdrCompleteData.builder()
                .frTempId(frTemp.getId())
                .codDominio(frTemp.getCodDominio())
                .codFlusso(frTemp.getCodFlusso())
                .codPsp(frTemp.getCodPsp())
                .revision(frTemp.getRevision())
                .stato(flowDetails.getStatus() != null ? flowDetails.getStatus().name() : null)
                .dataFlusso(convertToInstant(flowDetails.getFdrDate()))
                .dataRegolamento(convertToInstant(flowDetails.getRegulationDate()))
                .identificativoRegolamento(flowDetails.getRegulation())
                .bicRiversamento(flowDetails.getBicCodePouringBank())
                .numeroPagamenti(flowDetails.getTotPayments())
                .importoTotalePagamenti(flowDetails.getSumPayments() != null ? BigDecimal.valueOf(flowDetails.getSumPayments()) : null)
                .codPspMittente(flowDetails.getSender() != null ? flowDetails.getSender().getPspId() : null)
                .ragioneSocialePsp(flowDetails.getSender() != null ? flowDetails.getSender().getPspName() : null)
                .codIntermediarioPsp(flowDetails.getSender() != null ? flowDetails.getSender().getPspBrokerId() : null)
                .codCanale(flowDetails.getSender() != null ? flowDetails.getSender().getChannelId() : null)
                .dataPubblicazione(convertToInstant(flowDetails.getPublished()))
                .payments(paymentDataList)
                .build();

        } catch (RestClientException e) {
            log.error("Error processing FDR {}: {}", frTemp.getCodFlusso(), e.getMessage());
            throw e;
        }
    }

    private PaymentData convertPayment(Payment payment) {
        return PaymentData.builder()
            .iuv(payment.getIuv())
            .iur(payment.getIur())
            .indice(payment.getIndex())
            .importo(payment.getPay() != null ? BigDecimal.valueOf(payment.getPay()) : null)
            .esito(payment.getPayStatus() != null ? payment.getPayStatus().getValue() : null)
            .dataPagamento(convertToInstant(payment.getPayDate()))
            .build();
    }

    private Instant convertToInstant(OffsetDateTime offsetDateTime) {
        if (offsetDateTime == null) {
            return null;
        }
        return offsetDateTime.toInstant();
    }

    /**
     * DTO containing complete FDR data with payments
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class FdrCompleteData {
        private Long frTempId;
        private String codDominio;
        private String codFlusso;
        private String codPsp;
        private Long revision;
        private String stato;
        private Instant dataFlusso;
        private Instant dataRegolamento;
        private String identificativoRegolamento;
        private String bicRiversamento;
        private Long numeroPagamenti;
        private BigDecimal importoTotalePagamenti;
        private String codPspMittente;
        private String ragioneSocialePsp;
        private String codIntermediarioPsp;
        private String codCanale;
        private Instant dataPubblicazione;
        private List<PaymentData> payments;
    }

    @Data
    @Builder
    public static class PaymentData {
        private String iuv;
        private String iur;
        private Long indice;
        private BigDecimal importo;
        private String esito;
        private Instant dataPagamento;
    }
}
