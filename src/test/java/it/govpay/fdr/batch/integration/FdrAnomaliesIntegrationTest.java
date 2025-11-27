package it.govpay.fdr.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.fdr.batch.entity.Applicazione;
import it.govpay.fdr.batch.entity.Dominio;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.entity.Pagamento;
import it.govpay.fdr.batch.entity.Rendicontazione;
import it.govpay.fdr.batch.entity.SingoloVersamento;
import it.govpay.fdr.batch.entity.StatoFr;
import it.govpay.fdr.batch.entity.StatoRendicontazione;
import it.govpay.fdr.batch.entity.Versamento;
import it.govpay.fdr.batch.repository.ApplicazioneRepository;
import it.govpay.fdr.batch.repository.DominioRepository;
import it.govpay.fdr.batch.repository.FrRepository;
import it.govpay.fdr.batch.repository.FrTempRepository;
import it.govpay.fdr.batch.repository.PagamentoRepository;
import it.govpay.fdr.batch.repository.RendicontazioneRepository;
import it.govpay.fdr.batch.repository.SingoloVersamentoRepository;
import it.govpay.fdr.batch.repository.VersamentoRepository;
import it.govpay.fdr.batch.service.FdrApiService;
import it.govpay.fdr.batch.step3.FdrMetadataProcessor;
import it.govpay.fdr.batch.step3.FdrMetadataWriter;
import it.govpay.fdr.batch.step4.FdrPaymentsProcessor;
import it.govpay.fdr.batch.step4.FdrPaymentsWriter;
import it.govpay.fdr.client.model.Metadata;
import it.govpay.fdr.client.model.PaginatedPaymentsResponse;
import it.govpay.fdr.client.model.Payment;
import it.govpay.fdr.client.model.Receiver;
import it.govpay.fdr.client.model.Sender;
import it.govpay.fdr.client.model.SingleFlowResponse;

/**
 * Integration test for FDR anomalies and edge cases.
 * Tests handling of:
 * - IUV not found (ALTRO_INTERMEDIARIO)
 * - Duplicate rendicontazioni (ANOMALA)
 * - Amount mismatches (ANOMALA)
 * - Payment count mismatches (ANOMALA)
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.batch.job.enabled=false",
    "govpay.gde.enabled=false"
})
@Transactional
class FdrAnomaliesIntegrationTest {

    @Autowired
    private DominioRepository dominioRepository;

    @Autowired
    private ApplicazioneRepository applicazioneRepository;

    @Autowired
    private VersamentoRepository versamentoRepository;

    @Autowired
    private PagamentoRepository pagamentoRepository;

    @Autowired
    private SingoloVersamentoRepository singoloVersamentoRepository;

    @Autowired
    private FrTempRepository frTempRepository;

    @Autowired
    private FrRepository frRepository;

    @Autowired
    private RendicontazioneRepository rendicontazioneRepository;

    @Autowired
    private FdrMetadataProcessor metadataProcessor;

    @Autowired
    private FdrMetadataWriter metadataWriter;

    @Autowired
    private FdrPaymentsProcessor paymentsProcessor;

    @Autowired
    private FdrPaymentsWriter paymentsWriter;

    @MockBean
    private FdrApiService fdrApiService;

    private Dominio testDominio;
    private Applicazione testApplicazione;
    private static final String ORG_ID = "12345678901";
    private static final String FDR_ID = "2025-01-27PSP001-ANOM";
    private static final Long REVISION = 1L;
    private static final String PSP_ID = "PSP001";

    @BeforeEach
    void setUp() {
        // Create test domain with auxDigit=0 (monointermediato, IUV 15 cifre)
        testDominio = Dominio.builder()
            .codDominio(ORG_ID)
            .auxDigit(0)
            .build();
        testDominio = dominioRepository.save(testDominio);

        // Create test application
        testApplicazione = Applicazione.builder()
            .codApplicazione("APP_TEST")
            .build();
        testApplicazione = applicazioneRepository.save(testApplicazione);
    }

    @Test
    @DisplayName("Test FDR with IUV not found - should mark as ALTRO_INTERMEDIARIO")
    void testFdrWithIuvNotFound() throws Exception {
        // Given: FDR with 10 payments, none exist in database
        SingleFlowResponse flowResponse = createMockFlowResponse(10, 105.0);
        PaginatedPaymentsResponse paymentsResponse = createMockPaymentsResponse(10);

        mockApiCalls(flowResponse, paymentsResponse);

        // When: Process FDR
        processFdr(flowResponse);

        // Then: All rendicontazioni should be ALTRO_INTERMEDIARIO
        List<Rendicontazione> rendicontazioni = rendicontazioneRepository.findAll();
        assertThat(rendicontazioni).hasSize(10);

        long altroIntermediarioCount = rendicontazioni.stream()
            .filter(r -> r.getStato() == StatoRendicontazione.ALTRO_INTERMEDIARIO)
            .count();
        assertThat(altroIntermediarioCount).isEqualTo(10);

        // FR should still be accepted
        Fr fr = frRepository.findAll().get(0);
        assertThat(fr.getStato()).isEqualTo(StatoFr.ACCETTATA);
    }

    @Test
    @DisplayName("Test FDR with duplicate rendicontazioni within same flow - should mark as ANOMALA")
    void testFdrWithDuplicateRendicontazioni() throws Exception {
        // Given: Create versamento with 2 pagamenti for the same IUV/IUR (duplicate scenario)
        String iuv = String.format("%015d", 1);
        String iur = String.format("IUR%012d", 1);
        Versamento versamento = createVersamento(iuv);

        // Create TWO pagamenti with same IUV/IUR but different IDs
        // This simulates duplicate payments in the system
        Pagamento pagamento1 = createPagamento(versamento, iur);
        Pagamento pagamento2 = Pagamento.builder()
            .iur(iur)
            .importoPagato(10.50)
            .codDominio(ORG_ID)
            .iuv(versamento.getIuvPagamento())
            .indiceDati(1)
            .build();
        pagamento2 = pagamentoRepository.save(pagamento2);

        // Process FDR with DUPLICATE IUV/IUR within the same flow (2 payments with same IUV/IUR)
        SingleFlowResponse flowResponse = createMockFlowResponse(2, 21.0);  // 2 payments, total 21.0
        PaginatedPaymentsResponse paymentsResponse = createMockPaymentsResponseDuplicate();

        mockApiCalls(flowResponse, paymentsResponse);

        // When: Process FDR
        processFdr(flowResponse);

        // Then: Should have 2 rendicontazioni
        List<Rendicontazione> allRendicontazioni = rendicontazioneRepository.findAll();
        assertThat(allRendicontazioni).hasSize(2);

        // Both should be marked as ANOMALA because findAllPagamenti returns 2 results (duplicate scenario)
        // OR first is OK and second detects duplicate
        long anomalaCount = allRendicontazioni.stream()
            .filter(r -> r.getStato() == StatoRendicontazione.ANOMALA)
            .count();
        assertThat(anomalaCount).isGreaterThanOrEqualTo(1);

        // At least one should have duplicate-related anomaly
        boolean hasDuplicateAnomaly = allRendicontazioni.stream()
            .anyMatch(r -> r.getAnomalie() != null &&
                (r.getAnomalie().contains("duplicata") || r.getAnomalie().contains("piu di un pagamento")));
        assertThat(hasDuplicateAnomaly).isTrue();

        // FR should be marked as ANOMALA due to duplicate
        Fr fr = frRepository.findAll().get(0);
        assertThat(fr.getStato()).isEqualTo(StatoFr.ANOMALA);
    }

    @Test
    @DisplayName("Test FDR with amount mismatch - should mark FR as ANOMALA")
    void testFdrWithAmountMismatch() throws Exception {
        // Given: FDR declares 105.0 but contains payments totaling 100.0
        SingleFlowResponse flowResponse = createMockFlowResponse(10, 105.0);
        PaginatedPaymentsResponse paymentsResponse = createMockPaymentsResponse(10);
        // Modify last payment to create mismatch
        paymentsResponse.getData().get(9).setPay(5.50); // Total = 100.0 instead of 105.0

        mockApiCalls(flowResponse, paymentsResponse);

        // When: Process FDR
        processFdr(flowResponse);

        // Then: FR should be marked as ANOMALA
        Fr fr = frRepository.findAll().get(0);
        assertThat(fr.getStato()).isEqualTo(StatoFr.ANOMALA);
        assertThat(fr.getDescrizioneStato()).contains("somma degli importi");
    }

    @Test
    @DisplayName("Test FDR with payment count mismatch - should mark FR as ANOMALA")
    void testFdrWithPaymentCountMismatch() throws Exception {
        // Given: FDR declares 10 payments but contains only 8
        SingleFlowResponse flowResponse = createMockFlowResponse(10, 105.0);
        PaginatedPaymentsResponse paymentsResponse = createMockPaymentsResponse(8);

        mockApiCalls(flowResponse, paymentsResponse);

        // When: Process FDR
        processFdr(flowResponse);

        // Then: FR should be marked as ANOMALA
        Fr fr = frRepository.findAll().get(0);
        assertThat(fr.getStato()).isEqualTo(StatoFr.ANOMALA);
        assertThat(fr.getDescrizioneStato()).contains("numero di pagamenti");
    }

    @Test
    @DisplayName("Test mixed scenario: some OK, some ALTRO_INTERMEDIARIO, some ANOMALA")
    void testMixedScenario() throws Exception {
        // Given: Create 3 versamenti with payments (using 15-digit numeric IUVs - internal)
        for (int i = 1; i <= 3; i++) {
            String iuv = String.format("%015d", i);
            Versamento versamento = createVersamento(iuv);
            createPagamentoWithIndiceDati(versamento, String.format("IUR%012d", i), i);
        }

        // FDR with 10 payments: 3 exist (OK), 7 don't exist (ALTRO_INTERMEDIARIO)
        SingleFlowResponse flowResponse = createMockFlowResponse(10, 105.0);
        PaginatedPaymentsResponse paymentsResponse = createMockPaymentsResponseMixed();

        mockApiCalls(flowResponse, paymentsResponse);

        // When: Process FDR
        processFdr(flowResponse);

        // Then: Verify distribution of states
        List<Rendicontazione> rendicontazioni = rendicontazioneRepository.findAll();
        assertThat(rendicontazioni).hasSize(10);

        long okCount = rendicontazioni.stream()
            .filter(r -> r.getStato() == StatoRendicontazione.OK)
            .count();
        long altroIntermediarioCount = rendicontazioni.stream()
            .filter(r -> r.getStato() == StatoRendicontazione.ALTRO_INTERMEDIARIO)
            .count();

        assertThat(okCount).isEqualTo(3);
        assertThat(altroIntermediarioCount).isEqualTo(7);

        // FR should be ACCETTATA (no real anomalies)
        Fr fr = frRepository.findAll().get(0);
        assertThat(fr.getStato()).isEqualTo(StatoFr.ACCETTATA);
    }

    // Helper methods

    private void mockApiCalls(SingleFlowResponse flowResponse, PaginatedPaymentsResponse paymentsResponse) throws Exception {
        when(fdrApiService.getSinglePublishedFlow(eq(ORG_ID), eq(FDR_ID), eq(REVISION), eq(PSP_ID)))
            .thenReturn(flowResponse);

        when(fdrApiService.getPaymentsFromPublishedFlow(eq(ORG_ID), eq(FDR_ID), eq(REVISION), eq(PSP_ID)))
            .thenReturn(paymentsResponse.getData());
    }

    private void processFdr(SingleFlowResponse flowResponse) throws Exception {
        // Create FrTemp
        FrTemp frTemp = createFrTempRecord(flowResponse);
        frTemp = frTempRepository.save(frTemp);

        // Process metadata
        FdrMetadataProcessor.FdrCompleteData metadataData = metadataProcessor.process(frTemp);
        metadataWriter.write(new org.springframework.batch.item.Chunk<>(List.of(metadataData)));

        // Process payments
        FrTemp frTempReloaded = frTempRepository.findById(frTemp.getId()).orElseThrow();
        FdrPaymentsProcessor.FdrCompleteData paymentsData = paymentsProcessor.process(frTempReloaded);
        paymentsWriter.write(new org.springframework.batch.item.Chunk<>(List.of(paymentsData)));
    }

    private Versamento createVersamento(String iuv) {
        Versamento versamento = Versamento.builder()
            .dominio(testDominio)
            .applicazione(testApplicazione)
            .iuvVersamento(iuv)
            .iuvPagamento(iuv)
            .codVersamentoEnte("CODVERS_" + iuv)
            .statoVersamento("ESEGUITO")
            .build();
        return versamentoRepository.save(versamento);
    }

    private Pagamento createPagamento(Versamento versamento, String iur) {
        return createPagamentoWithIndiceDati(versamento, iur, 1);
    }

    private Pagamento createPagamentoWithIndiceDati(Versamento versamento, String iur, int indiceDati) {
        // Create SingoloVersamento first
        SingoloVersamento singoloVersamento = SingoloVersamento.builder()
            .versamento(versamento)
            .indiceDati(indiceDati)
            .build();
        singoloVersamento = singoloVersamentoRepository.save(singoloVersamento);

        // Create Pagamento linked to SingoloVersamento
        Pagamento pagamento = Pagamento.builder()
            .singoloVersamento(singoloVersamento)
            .iur(iur)
            .importoPagato(10.50)
            .codDominio(ORG_ID)
            .iuv(versamento.getIuvPagamento())
            .indiceDati(indiceDati)
            .build();
        return pagamentoRepository.save(pagamento);
    }

    private Fr createExistingFr() {
        return Fr.builder()
            .codPsp(PSP_ID)
            .dominio(testDominio)
            .codDominio(ORG_ID)
            .codFlusso("EXISTING-FDR")
            .stato(StatoFr.ACCETTATA)
            .dataOraFlusso(Instant.now())
            .dataRegolamento(Instant.now())
            .numeroPagamenti(1L)
            .importoTotalePagamenti(10.50)
            .revisione(1L)
            .obsoleto(false)
            .build();
    }

    private SingleFlowResponse createMockFlowResponse(int paymentCount, double totalAmount) {
        SingleFlowResponse response = new SingleFlowResponse();
        response.setFdr(FDR_ID);
        response.setRevision(REVISION);
        response.setFdrDate(OffsetDateTime.now(ZoneOffset.UTC));
        response.setRegulationDate(LocalDate.now());
        response.setTotPayments((long) paymentCount);
        response.setSumPayments(totalAmount);
        response.setComputedTotPayments((long) paymentCount);
        response.setComputedSumPayments(totalAmount);
        response.setPublished(OffsetDateTime.now(ZoneOffset.UTC));
        response.setRegulation("SEPA - Bonifico");
        response.setBicCodePouringBank("UNCRITMMXXX");

        Sender sender = new Sender();
        sender.setPspId(PSP_ID);
        sender.setPspName("PSP Test");
        response.setSender(sender);

        Receiver receiver = new Receiver();
        receiver.setOrganizationId(ORG_ID);
        receiver.setOrganizationName("Comune di Test");
        response.setReceiver(receiver);

        return response;
    }

    private PaginatedPaymentsResponse createMockPaymentsResponse(int count) {
        PaginatedPaymentsResponse response = new PaginatedPaymentsResponse();

        Metadata metadata = new Metadata();
        metadata.setPageSize(count);
        metadata.setPageNumber(1);
        metadata.setTotPage(1);
        response.setMetadata(metadata);

        response.setCount((long) count);

        List<Payment> payments = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Payment payment = new Payment();
            payment.setIndex((long) i);
            // Use non-internal IUV format (alphanumeric) for ALTRO_INTERMEDIARIO tests
            payment.setIuv(String.format("RF%013d", i));
            payment.setIur(String.format("IUR%012d", i));
            payment.setIdTransfer(1L);
            payment.setPay(10.50);
            payment.setPayStatus(Payment.PayStatusEnum.EXECUTED);
            payment.setPayDate(OffsetDateTime.now(ZoneOffset.UTC));
            payments.add(payment);
        }
        response.setData(payments);

        return response;
    }

    private PaginatedPaymentsResponse createMockPaymentsResponseInternal(int count) {
        PaginatedPaymentsResponse response = new PaginatedPaymentsResponse();

        Metadata metadata = new Metadata();
        metadata.setPageSize(count);
        metadata.setPageNumber(1);
        metadata.setTotPage(1);
        response.setMetadata(metadata);

        response.setCount((long) count);

        List<Payment> payments = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Payment payment = new Payment();
            payment.setIndex((long) i);
            // Internal IUV format: 15-digit numeric
            payment.setIuv(String.format("%015d", i));
            payment.setIur(String.format("IUR%012d", i));
            payment.setIdTransfer(1L);
            payment.setPay(10.50);
            payment.setPayStatus(Payment.PayStatusEnum.EXECUTED);
            payment.setPayDate(OffsetDateTime.now(ZoneOffset.UTC));
            payments.add(payment);
        }
        response.setData(payments);

        return response;
    }

    private PaginatedPaymentsResponse createMockPaymentsResponseDuplicate() {
        PaginatedPaymentsResponse response = new PaginatedPaymentsResponse();

        Metadata metadata = new Metadata();
        metadata.setPageSize(2);
        metadata.setPageNumber(1);
        metadata.setTotPage(1);
        response.setMetadata(metadata);

        response.setCount(2L);

        List<Payment> payments = new ArrayList<>();

        // First payment
        Payment payment1 = new Payment();
        payment1.setIndex(1L);
        payment1.setIuv(String.format("%015d", 1));
        payment1.setIur(String.format("IUR%012d", 1));
        payment1.setIdTransfer(1L);
        payment1.setPay(10.50);
        payment1.setPayStatus(Payment.PayStatusEnum.EXECUTED);
        payment1.setPayDate(OffsetDateTime.now(ZoneOffset.UTC));
        payments.add(payment1);

        // Second payment - DUPLICATE (same IUV/IUR/indiceDati)
        Payment payment2 = new Payment();
        payment2.setIndex(2L);
        payment2.setIuv(String.format("%015d", 1));  // Same IUV
        payment2.setIur(String.format("IUR%012d", 1));  // Same IUR
        payment2.setIdTransfer(1L);  // Same indiceDati
        payment2.setPay(10.50);
        payment2.setPayStatus(Payment.PayStatusEnum.EXECUTED);
        payment2.setPayDate(OffsetDateTime.now(ZoneOffset.UTC));
        payments.add(payment2);

        response.setData(payments);

        return response;
    }

    private PaginatedPaymentsResponse createMockPaymentsResponseMixed() {
        PaginatedPaymentsResponse response = new PaginatedPaymentsResponse();

        Metadata metadata = new Metadata();
        metadata.setPageSize(10);
        metadata.setPageNumber(1);
        metadata.setTotPage(1);
        response.setMetadata(metadata);

        response.setCount(10L);

        List<Payment> payments = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            Payment payment = new Payment();
            payment.setIndex((long) i);

            // First 3: internal IUVs (15-digit numeric) with unique indiceDati - should match Versamenti
            // Last 7: non-internal IUVs (alphanumeric) - should be ALTRO_INTERMEDIARIO
            if (i <= 3) {
                payment.setIuv(String.format("%015d", i));
                payment.setIdTransfer((long) i);  // Unique indiceDati for each payment
            } else {
                payment.setIuv(String.format("RF%013d", i));
                payment.setIdTransfer(1L);
            }
            payment.setIur(String.format("IUR%012d", i));
            payment.setPay(10.50);
            payment.setPayStatus(Payment.PayStatusEnum.EXECUTED);
            payment.setPayDate(OffsetDateTime.now(ZoneOffset.UTC));
            payments.add(payment);
        }
        response.setData(payments);

        return response;
    }

    private FrTemp createFrTempRecord(SingleFlowResponse response) {
        return FrTemp.builder()
            .idPsp(PSP_ID)
            .codDominio(ORG_ID)
            .codPsp(PSP_ID)
            .codFlusso(FDR_ID)
            .stato("ACQUISITO")
            .dataOraFlusso(response.getFdrDate().toInstant())
            .dataRegolamento(response.getRegulationDate().atStartOfDay().toInstant(ZoneOffset.UTC))
            .numeroPagamenti(response.getTotPayments())
            .importoTotalePagamenti(response.getSumPayments())
            .codBicRiversamento(response.getBicCodePouringBank())
            .iur(response.getRegulation())
            .ragioneSocialePsp(response.getSender() != null ? response.getSender().getPspName() : null)
            .ragioneSocialeDominio(response.getReceiver() != null ? response.getReceiver().getOrganizationName() : null)
            .dataOraPubblicazione(response.getPublished() != null ? response.getPublished().toInstant() : null)
            .dataOraAggiornamento(response.getUpdated() != null ? response.getUpdated().toInstant() : null)
            .revisione(response.getRevision())
            .build();
    }
}
