package it.govpay.fdr.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.entity.StatoFr;
import it.govpay.fdr.batch.repository.FrRepository;
import it.govpay.fdr.batch.repository.FrTempRepository;
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
 * Integration test per la gestione del flag obsoleto e della revisione dei flussi FR.
 * Verifica che quando si acquisisce un flusso con revisione > 1, i flussi precedenti
 * con la stessa chiave (codDominio, codFlusso, codPsp) vengano marcati come obsoleti.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.batch.job.enabled=false",
})
@Transactional
class FdrObsoletoIntegrationTest {

    @Autowired
    private DominioRepository dominioRepository;

    @Autowired
    private StazioneRepository stazioneRepository;

    @Autowired
    private FrTempRepository frTempRepository;

    @Autowired
    private FrRepository frRepository;

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

    private DominioEntity testDominio;
    private static final String ORG_ID = "12345678901";
    private static final String FDR_ID = "2025-01-27PSP001-0001";
    private static final String PSP_ID = "PSP001";

    @BeforeEach
    void setUp() {
        StazioneEntity stazione = stazioneRepository.findByCodStazione("12345678901_01").orElseThrow();

        testDominio = DominioEntity.builder()
            .codDominio(ORG_ID)
            .auxDigit(0)
            .abilitato(true)
            .ragioneSociale("Comune di Test")
            .intermediato(true)
            .scaricaFr(true)
            .stazione(stazione)
            .build();
        testDominio = dominioRepository.save(testDominio);
    }

    @Test
    @DisplayName("Revisione 1: il flusso viene salvato con obsoleto=false")
    void testRevisione1ObsoletoFalse() throws Exception {
        // Given
        acquisisciFlussoCon(1L, 3);

        // Then
        List<Fr> frList = frRepository.findAll();
        assertThat(frList).hasSize(1);
        Fr fr = frList.get(0);
        assertThat(fr.getRevisione()).isEqualTo(1L);
        assertThat(fr.getObsoleto()).isFalse();
    }

    @Test
    @DisplayName("Revisione 2: il flusso precedente (rev 1) viene marcato come obsoleto")
    void testRevisione2MarcaObsoletoPrecedente() throws Exception {
        // Given - acquisisco revisione 1
        acquisisciFlussoCon(1L, 3);

        // When - acquisisco revisione 2
        acquisisciFlussoCon(2L, 3);

        // Then
        List<Fr> frList = frRepository.findAll();
        assertThat(frList).hasSize(2);

        Fr frRev1 = frList.stream().filter(f -> f.getRevisione() == 1L).findFirst().orElseThrow();
        Fr frRev2 = frList.stream().filter(f -> f.getRevisione() == 2L).findFirst().orElseThrow();

        assertThat(frRev1.getObsoleto()).isTrue();
        assertThat(frRev2.getObsoleto()).isFalse();
    }

    @Test
    @DisplayName("Revisione 3: tutti i flussi precedenti (rev 1 e 2) vengono marcati come obsoleti")
    void testRevisione3MarcaObsoletiTuttiPrecedenti() throws Exception {
        // Given - acquisisco revisione 1 e 2
        acquisisciFlussoCon(1L, 3);
        acquisisciFlussoCon(2L, 3);

        // When - acquisisco revisione 3
        acquisisciFlussoCon(3L, 3);

        // Then
        List<Fr> frList = frRepository.findAll();
        assertThat(frList).hasSize(3);

        Fr frRev1 = frList.stream().filter(f -> f.getRevisione() == 1L).findFirst().orElseThrow();
        Fr frRev2 = frList.stream().filter(f -> f.getRevisione() == 2L).findFirst().orElseThrow();
        Fr frRev3 = frList.stream().filter(f -> f.getRevisione() == 3L).findFirst().orElseThrow();

        assertThat(frRev1.getObsoleto()).isTrue();
        assertThat(frRev2.getObsoleto()).isTrue();
        assertThat(frRev3.getObsoleto()).isFalse();
    }

    @Test
    @DisplayName("Flussi di domini/PSP diversi non vengono marcati come obsoleti")
    void testObsoletoNonInfluenzaAltriFlussi() throws Exception {
        // Given - acquisisco flusso per PSP001
        acquisisciFlussoCon(1L, 3);

        // Acquisisco un flusso diverso (altro codFlusso) per lo stesso dominio e PSP
        String otherFdrId = "2025-02-15PSP001-0002";
        acquisisciFlussoCon(otherFdrId, PSP_ID, 1L, 2);

        // When - acquisisco revisione 2 del primo flusso
        acquisisciFlussoCon(2L, 3);

        // Then
        List<Fr> frList = frRepository.findAll();
        assertThat(frList).hasSize(3);

        // Il flusso con altro codFlusso NON deve essere obsoleto
        Fr frAltro = frList.stream()
            .filter(f -> f.getCodFlusso().equals(otherFdrId))
            .findFirst().orElseThrow();
        assertThat(frAltro.getObsoleto()).isFalse();

        // Il flusso rev 1 dello stesso codFlusso DEVE essere obsoleto
        Fr frRev1 = frList.stream()
            .filter(f -> f.getCodFlusso().equals(FDR_ID) && f.getRevisione() == 1L)
            .findFirst().orElseThrow();
        assertThat(frRev1.getObsoleto()).isTrue();
    }

    // --- Metodi helper ---

    private void acquisisciFlussoCon(Long revisione, int numPayments) throws Exception {
        acquisisciFlussoCon(FDR_ID, PSP_ID, revisione, numPayments);
    }

    private void acquisisciFlussoCon(String fdrId, String pspId, Long revisione, int numPayments) throws Exception {
        SingleFlowResponse flowResponse = createMockFlowResponse(fdrId, pspId, revisione, numPayments);
        PaginatedPaymentsResponse paymentsResponse = createMockPaymentsResponse(numPayments);

        when(fdrApiService.getSinglePublishedFlow(any(), any(), any(), any()))
            .thenReturn(flowResponse);
        when(fdrApiService.getPaymentsFromPublishedFlow(any(), any(), any(), any()))
            .thenReturn(paymentsResponse.getData());

        FrTemp frTemp = createFrTempRecord(fdrId, pspId, revisione, flowResponse);
        frTemp = frTempRepository.save(frTemp);

        // Metadata step
        FdrMetadataProcessor.FdrCompleteData completeData = metadataProcessor.process(frTemp);
        metadataWriter.write(new org.springframework.batch.item.Chunk<>(List.of(completeData)));

        // Payments step
        FrTemp frTempReloaded = frTempRepository.findById(frTemp.getId()).orElseThrow();
        FdrPaymentsProcessor.FdrCompleteData paymentsData = paymentsProcessor.process(frTempReloaded);
        paymentsWriter.write(new org.springframework.batch.item.Chunk<>(List.of(paymentsData)));
    }

    private SingleFlowResponse createMockFlowResponse(String fdrId, String pspId, Long revisione, int numPayments) {
        SingleFlowResponse response = new SingleFlowResponse();
        response.setFdr(fdrId);
        response.setRevision(revisione);
        response.setFdrDate(OffsetDateTime.now(ZoneOffset.UTC));
        response.setRegulationDate(LocalDate.now());
        response.setTotPayments((long) numPayments);
        response.setSumPayments(numPayments * 10.50);
        response.setComputedTotPayments((long) numPayments);
        response.setComputedSumPayments(numPayments * 10.50);
        response.setPublished(OffsetDateTime.now(ZoneOffset.UTC));
        response.setCreated(OffsetDateTime.now(ZoneOffset.UTC));
        response.setUpdated(OffsetDateTime.now(ZoneOffset.UTC));
        response.setRegulation("SEPA - Bonifico");
        response.setBicCodePouringBank("UNCRITMMXXX");

        Sender sender = new Sender();
        sender.setPspId(pspId);
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

    private FrTemp createFrTempRecord(String fdrId, String pspId, Long revisione, SingleFlowResponse response) {
        return FrTemp.builder()
            .idPsp(pspId)
            .codDominio(ORG_ID)
            .codPsp(pspId)
            .codFlusso(fdrId)
            .stato("ACQUISITO")
            .dataOraFlusso(response.getFdrDate().toLocalDateTime())
            .dataRegolamento(response.getRegulationDate().atStartOfDay())
            .numeroPagamenti(response.getTotPayments())
            .importoTotalePagamenti(response.getSumPayments())
            .codBicRiversamento(response.getBicCodePouringBank())
            .iur(response.getRegulation())
            .ragioneSocialePsp(response.getSender() != null ? response.getSender().getPspName() : null)
            .ragioneSocialeDominio(response.getReceiver() != null ? response.getReceiver().getOrganizationName() : null)
            .dataOraPubblicazione(response.getPublished() != null ? response.getPublished().toLocalDateTime() : null)
            .dataOraAggiornamento(response.getUpdated() != null ? response.getUpdated().toLocalDateTime() : null)
            .revisione(revisione)
            .build();
    }
}
