package it.govpay.fdr.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.fdr.batch.entity.Dominio;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.entity.Rendicontazione;
import it.govpay.fdr.batch.entity.StatoFr;
import it.govpay.fdr.batch.entity.StatoRendicontazione;
import it.govpay.fdr.batch.repository.DominioRepository;
import it.govpay.fdr.batch.repository.FrRepository;
import it.govpay.fdr.batch.repository.FrTempRepository;
import it.govpay.fdr.batch.repository.RendicontazioneRepository;
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
 * Integration test for FDR download and processing with 10 rendicontazioni.
 * Tests the complete flow from API download to database persistence.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.batch.job.enabled=false",
    "govpay.gde.enabled=false"
})
@Transactional
class FdrDownloadIntegrationTest {

    @Autowired
    private DominioRepository dominioRepository;

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
    private static final String ORG_ID = "12345678901";
    private static final String FDR_ID = "2025-01-27PSP001-0001";
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
    }

    @Test
    @DisplayName("Test complete FDR download and processing with 10 rendicontazioni")
    void testCompleteFdrDownloadWith10Rendicontazioni() throws Exception {
        // Given: Mock API responses
        SingleFlowResponse flowResponse = createMockFlowResponse();
        PaginatedPaymentsResponse paymentsResponse = createMockPaymentsResponse(10);

        when(fdrApiService.getSinglePublishedFlow(eq(ORG_ID), eq(FDR_ID), eq(REVISION), eq(PSP_ID)))
            .thenReturn(flowResponse);

        when(fdrApiService.getPaymentsFromPublishedFlow(eq(ORG_ID), eq(FDR_ID), eq(REVISION), eq(PSP_ID)))
            .thenReturn(paymentsResponse.getData());

        // Step 1: Create FrTemp record (simulating step 2 and 3)
        FrTemp frTemp = createFrTempRecord(flowResponse);
        frTemp = frTempRepository.save(frTemp);

        // Step 2: Process FDR metadata (Step 3)
        FdrMetadataProcessor.FdrCompleteData completeData = metadataProcessor.process(frTemp);
        assertThat(completeData).isNotNull();
        assertThat(completeData.getCodFlusso()).isEqualTo(FDR_ID);
        assertThat(completeData.getNumeroPagamenti()).isEqualTo(10L);

        // Step 3: Write FDR to database (Step 3 Writer)
        metadataWriter.write(new org.springframework.batch.item.Chunk<>(List.of(completeData)));

        // Step 4: Process and write payments (Step 4)
        FrTemp frTempForPayments = frTempRepository.findById(frTemp.getId()).orElseThrow();
        FdrPaymentsProcessor.FdrCompleteData paymentsData = paymentsProcessor.process(frTempForPayments);
        assertThat(paymentsData).isNotNull();

        paymentsWriter.write(new org.springframework.batch.item.Chunk<>(List.of(paymentsData)));

        // Step 5: Verify Fr was created
        List<Fr> frList = frRepository.findAll();
        assertThat(frList).hasSize(1);
        Fr savedFr = frList.get(0);
        assertFrMetadata(savedFr, flowResponse);

        // Step 6: Verify all rendicontazioni were created
        List<Rendicontazione> rendicontazioni = rendicontazioneRepository.findAll();
        assertThat(rendicontazioni).hasSize(10);

        // Verify each rendicontazione
        for (int i = 0; i < 10; i++) {
            Rendicontazione rend = rendicontazioni.get(i);
            assertThat(rend.getFr()).isNotNull();
            assertThat(rend.getFr().getId()).isEqualTo(savedFr.getId());
            assertThat(rend.getIuv()).isEqualTo(String.format("RF%013d", i + 1));
            assertThat(rend.getIur()).isEqualTo(String.format("IUR%012d", i + 1));
            assertThat(rend.getImportoPagato()).isEqualTo(10.50);
            assertThat(rend.getEsito()).isZero(); // EXECUTED
            // Non-internal IUVs (alphanumeric) should be ALTRO_INTERMEDIARIO
            assertThat(rend.getStato()).isEqualTo(StatoRendicontazione.ALTRO_INTERMEDIARIO);
        }

        // Step 7: Verify final Fr statistics
        Fr finalFr = frRepository.findById(savedFr.getId()).orElseThrow();
        assertThat(finalFr.getNumeroPagamenti()).isEqualTo(10L);
        assertThat(finalFr.getImportoTotalePagamenti()).isEqualTo(105.0); // 10 * 10.50
        assertThat(finalFr.getStato()).isIn(StatoFr.ACCETTATA, StatoFr.ANOMALA);
        assertThat(finalFr.getRendicontazioni()).hasSize(10);
    }

    @Test
    @DisplayName("Test FDR metadata processing")
    void testFdrMetadataProcessing() throws Exception {
        // Given
        SingleFlowResponse flowResponse = createMockFlowResponse();
        when(fdrApiService.getSinglePublishedFlow(any(), any(), any(), any()))
            .thenReturn(flowResponse);

        FrTemp frTemp = createFrTempRecord(flowResponse);
        frTemp = frTempRepository.save(frTemp);

        // When
        FdrMetadataProcessor.FdrCompleteData result = metadataProcessor.process(frTemp);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getCodFlusso()).isEqualTo(FDR_ID);
        assertThat(result.getCodPsp()).isEqualTo(PSP_ID);
        assertThat(result.getCodDominio()).isEqualTo(ORG_ID);
        assertThat(result.getRevisione()).isEqualTo(REVISION);
        assertThat(result.getNumeroPagamenti()).isEqualTo(10L);
        assertThat(result.getImportoTotalePagamenti()).isEqualTo(105.0);
    }

    @Test
    @DisplayName("Test payments processing and validation")
    void testPaymentsProcessingAndValidation() throws Exception {
        // Given: Create complete FR
        SingleFlowResponse flowResponse = createMockFlowResponse();
        PaginatedPaymentsResponse paymentsResponse = createMockPaymentsResponse(10);

        when(fdrApiService.getSinglePublishedFlow(any(), any(), any(), any()))
            .thenReturn(flowResponse);
        when(fdrApiService.getPaymentsFromPublishedFlow(any(), any(), any(), any()))
            .thenReturn(paymentsResponse.getData());

        FrTemp frTemp = createFrTempRecord(flowResponse);
        frTemp = frTempRepository.save(frTemp);

        // Process metadata first
        FdrMetadataProcessor.FdrCompleteData metadataResult = metadataProcessor.process(frTemp);
        metadataWriter.write(new org.springframework.batch.item.Chunk<>(List.of(metadataResult)));

        // When: Process payments
        FrTemp frTempReloaded = frTempRepository.findById(frTemp.getId()).orElseThrow();
        FdrPaymentsProcessor.FdrCompleteData paymentsResult = paymentsProcessor.process(frTempReloaded);
        paymentsWriter.write(new org.springframework.batch.item.Chunk<>(List.of(paymentsResult)));

        // Then: Verify payments were processed correctly
        List<Rendicontazione> rendicontazioni = rendicontazioneRepository.findAll();
        assertThat(rendicontazioni).hasSize(10);

        // Without Versamento/Pagamento records, all should be ALTRO_INTERMEDIARIO
        long altroIntermediarioCount = rendicontazioni.stream()
            .filter(r -> r.getStato() == StatoRendicontazione.ALTRO_INTERMEDIARIO)
            .count();

        assertThat(altroIntermediarioCount).isEqualTo(10);
    }

    private SingleFlowResponse createMockFlowResponse() {
        SingleFlowResponse response = new SingleFlowResponse();
        response.setFdr(FDR_ID);
        response.setRevision(REVISION);
        response.setFdrDate(OffsetDateTime.now(ZoneOffset.UTC));
        response.setRegulationDate(LocalDate.now());
        response.setTotPayments(10L);
        response.setSumPayments(105.0);
        response.setComputedTotPayments(10L);
        response.setComputedSumPayments(105.0);
        response.setPublished(OffsetDateTime.now(ZoneOffset.UTC));
        response.setCreated(OffsetDateTime.now(ZoneOffset.UTC));
        response.setUpdated(OffsetDateTime.now(ZoneOffset.UTC));
        response.setRegulation("SEPA - Bonifico");
        response.setBicCodePouringBank("UNCRITMMXXX");

        // Sender (PSP)
        Sender sender = new Sender();
        sender.setPspId(PSP_ID);
        sender.setPspName("PSP Test");
        response.setSender(sender);

        // Receiver (Organization)
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
            // Use non-internal IUV format (alphanumeric) so they're marked as ALTRO_INTERMEDIARIO
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

    private void assertFrMetadata(Fr fr, SingleFlowResponse expected) {
        assertThat(fr.getCodFlusso()).isEqualTo(expected.getFdr());
        assertThat(fr.getCodPsp()).isEqualTo(PSP_ID);
        assertThat(fr.getCodDominio()).isEqualTo(ORG_ID);
        assertThat(fr.getRevisione()).isEqualTo(expected.getRevision());
        assertThat(fr.getNumeroPagamenti()).isEqualTo(expected.getTotPayments());
        assertThat(fr.getImportoTotalePagamenti()).isEqualTo(expected.getSumPayments());
        assertThat(fr.getDominio()).isNotNull();
        assertThat(fr.getDominio().getCodDominio()).isEqualTo(ORG_ID);
    }
}
