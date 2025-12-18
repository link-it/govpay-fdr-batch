package it.govpay.fdr.batch.step4;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.service.FdrApiService;
import it.govpay.fdr.client.model.Payment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Processor to fetch FDR details and payments from pagoPA API
 */
@Component
@Slf4j
public class FdrPaymentsProcessor implements ItemProcessor<FrTemp, FdrPaymentsProcessor.FdrCompleteData> {

    private final FdrApiService fdrApiService;
    private final ZoneId applicationZoneId;

    public FdrPaymentsProcessor(FdrApiService fdrApiService, ZoneId applicationZoneId) {
        this.fdrApiService = fdrApiService;
        this.applicationZoneId = applicationZoneId;
    }

    @Override
    public FdrCompleteData process(FrTemp frTemp) throws Exception {
        log.info("Processing FDR: domain={}, flow={}, revision={}, psp={}",
            frTemp.getCodDominio(), frTemp.getCodFlusso(), frTemp.getRevisione(), frTemp.getIdPsp());

        try {
            // Fetch payments
            List<Payment> payments = fdrApiService.getPaymentsFromPublishedFlow(
                frTemp.getCodDominio(),
                frTemp.getCodFlusso(),
                frTemp.getRevisione(),
                frTemp.getIdPsp()
            );

            log.info("Recuperati {} pagamenti per FDR {}", payments.size(), frTemp.getCodFlusso());

            // Convert to internal model
            List<PaymentData> paymentDataList = payments.stream()
                .map(this::convertPayment)
                .toList();

            FdrCompleteData result = FdrCompleteData.builder()
                .frTempId(frTemp.getId())
                .codPsp(frTemp.getIdPsp())
                .codDominio(frTemp.getCodDominio())
                .codFlusso(frTemp.getCodFlusso())
                .iur(frTemp.getIur())
                .dataOraFlusso(frTemp.getDataOraFlusso())
                .dataRegolamento(frTemp.getDataRegolamento())
                .numeroPagamenti(frTemp.getNumeroPagamenti())
                .importoTotalePagamenti(frTemp.getImportoTotalePagamenti())
                .codBicRiversamento(frTemp.getCodBicRiversamento())
                .ragioneSocialePsp(frTemp.getRagioneSocialePsp())
                .ragioneSocialeDominio(frTemp.getRagioneSocialeDominio())
                .dataOraPubblicazione(frTemp.getDataOraPubblicazione())
                .dataOraAggiornamento(frTemp.getDataOraAggiornamento())
                .revisione(frTemp.getRevisione())
                .stato(frTemp.getStato())
                .payments(paymentDataList)
                .build();

            log.debug("FdrCompleteData creato - Flusso: {}, IUR: {}, Dominio: {}, PSP: {}, Revisione: {}, NumPagamenti: {}, ImportoTotale: {}, Payments in lista: {}",
                result.getCodFlusso(),
                result.getIur(),
                result.getCodDominio(),
                result.getCodPsp(),
                result.getRevisione(),
                result.getNumeroPagamenti(),
                result.getImportoTotalePagamenti(),
                result.getPayments() != null ? result.getPayments().size() : 0);

            return result;

        } catch (RestClientException e) {
            log.error("Errore nell'elaborazione dell'FDR {}: {}", frTemp.getCodFlusso(), e.getMessage());
            throw e;
        }
    }

    private PaymentData convertPayment(Payment payment) {
        return PaymentData.builder()
            .iuv(payment.getIuv())
            .iur(payment.getIur())
            .indiceDati(payment.getIndex())
            .importoPagato(payment.getPay())
            .esito(convertPayStatusToInteger(payment.getPayStatus()))
            .data(convertToLocalDateTime(payment.getPayDate()))
            .build();
    }

    /**
     * Convert PayStatus enum to integer code as stored in database
     * EXECUTED -> 0, REVOKED -> 3, STAND_IN -> 4, STAND_IN_NO_RPT -> 8, NO_RPT -> 9
     */
    private Integer convertPayStatusToInteger(Payment.PayStatusEnum payStatus) {
        if (payStatus == null) {
            return null;
        }
        return switch (payStatus) {
            case EXECUTED -> Costanti.PAYMENT_EXECUTED;
            case REVOKED -> Costanti.PAYMENT_REVOKED;
            case STAND_IN -> Costanti.PAYMENT_STAND_IN;
            case STAND_IN_NO_RPT -> Costanti.PAYMENT_STAND_IN_NO_RPT;
            case NO_RPT -> Costanti.PAYMENT_NO_RPT;
        };
    }

    private LocalDateTime convertToLocalDateTime(OffsetDateTime offsetDateTime) {
        if (offsetDateTime == null) {
            return null;
        }
        return offsetDateTime.atZoneSameInstant(applicationZoneId).toLocalDateTime();
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
        private LocalDateTime dataOraFlusso;
        private LocalDateTime dataRegolamento;
        private Long numeroPagamenti;
        private Double importoTotalePagamenti;
        private String codBicRiversamento;
        private String codPspMittente;
        private String ragioneSocialePsp;
        private String ragioneSocialeDominio;
        private String codIntermediarioPsp;
        private String codCanale;
        private LocalDateTime dataOraPubblicazione;
        private LocalDateTime dataOraAggiornamento;
        private Long revisione;
        private String stato;
        private List<PaymentData> payments;
    }

    @Data
    @Builder
    public static class PaymentData {
        private String iuv;
        private String iur;
        private Long indiceDati;
        private Double importoPagato;
        private Integer esito;
        private LocalDateTime data;
    }
}
