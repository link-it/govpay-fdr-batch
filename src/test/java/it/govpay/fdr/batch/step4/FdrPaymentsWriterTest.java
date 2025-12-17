package it.govpay.fdr.batch.step4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.entity.Applicazione;
import it.govpay.fdr.batch.entity.Dominio;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.entity.Pagamento;
import it.govpay.fdr.batch.entity.SingoloVersamento;
import it.govpay.fdr.batch.entity.StatoFr;
import it.govpay.fdr.batch.entity.Versamento;
import it.govpay.fdr.batch.repository.DominioRepository;
import it.govpay.fdr.batch.repository.FrRepository;
import it.govpay.fdr.batch.repository.FrTempRepository;
import it.govpay.fdr.batch.repository.PagamentoRepository;
import it.govpay.fdr.batch.repository.SingoloVersamentoRepository;
import it.govpay.fdr.batch.repository.VersamentoRepository;

/**
 * Unit tests for FdrPaymentsWriter.
 */
@ExtendWith(MockitoExtension.class)
class FdrPaymentsWriterTest {

    @Mock
    private FrRepository frRepository;

    @Mock
    private DominioRepository dominioRepository;

    @Mock
    private PagamentoRepository pagamentoRepository;

    @Mock
    private VersamentoRepository versamentoRepository;

    @Mock
    private SingoloVersamentoRepository singoloVersamentoRepository;

    @Mock
    private FrTempRepository frTempRepository;

    @Captor
    private ArgumentCaptor<Fr> frCaptor;

    private FdrPaymentsWriter writer;

    private Dominio testDominio;
    private FdrPaymentsProcessor.FdrCompleteData testData;

    @BeforeEach
    void setUp() {
        writer = new FdrPaymentsWriter(
            frRepository,
            dominioRepository,
            pagamentoRepository,
            versamentoRepository,
            singoloVersamentoRepository,
            frTempRepository
        );

        testDominio = Dominio.builder()
            .id(1L)
            .codDominio("12345678901")
            .auxDigit(0)
            .segregationCode(null)
            .scaricaFr(true)
            .build();

        testData = createTestFdrCompleteData();
    }

    private FdrPaymentsProcessor.FdrCompleteData createTestFdrCompleteData() {
        List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
        payments.add(FdrPaymentsProcessor.PaymentData.builder()
            .iuv("IUV001")
            .iur("IUR001")
            .indiceDati(1L)
            .importoPagato(100.00)
            .esito(Costanti.PAYMENT_EXECUTED)
            .data(Instant.now())
            .build());

        return FdrPaymentsProcessor.FdrCompleteData.builder()
            .frTempId(1L)
            .codPsp("PSP001")
            .codDominio("12345678901")
            .codFlusso("FDR-TEST-001")
            .iur("IUR-FLUSSO")
            .dataOraFlusso(Instant.now())
            .dataRegolamento(Instant.now())
            .numeroPagamenti(1L)
            .importoTotalePagamenti(100.00)
            .codBicRiversamento("BIC001")
            .ragioneSocialePsp("PSP Test")
            .ragioneSocialeDominio("Dominio Test")
            .dataOraPubblicazione(Instant.now())
            .dataOraAggiornamento(Instant.now())
            .revisione(1L)
            .stato("PUBLISHED")
            .payments(payments)
            .build();
    }

    @Nested
    class WriteBasicTests {

        @Test
        void testWriteSuccessWithPaymentMatch() {
            // Given
            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .codDominio("12345678901")
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1)
                .importoPagato(100.00)
                .singoloVersamento(sv)
                .build();

            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(testData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            assertThat(savedFr.getCodFlusso()).isEqualTo("FDR-TEST-001");
            assertThat(savedFr.getCodPsp()).isEqualTo("PSP001");
            assertThat(savedFr.getStato()).isEqualTo(StatoFr.ACCETTATA);
            assertThat(savedFr.getRendicontazioni()).hasSize(1);

            verify(frTempRepository).delete(frTemp);
        }

        @Test
        void testWriteSkipsExistingFr() {
            // Given
            Fr existingFr = Fr.builder()
                .id(1L)
                .codFlusso("FDR-TEST-001")
                .codPsp("PSP001")
                .revisione(1L)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione("FDR-TEST-001", "PSP001", 1L))
                .thenReturn(Optional.of(existingFr));

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(testData));
            writer.write(chunk);

            // Then
            verify(frRepository, never()).save(any(Fr.class));
            verify(frTempRepository).delete(frTemp);
        }

        @Test
        void testWriteSkipsWhenDominioNotFound() {
            // Given
            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.empty());

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(testData));
            writer.write(chunk);

            // Then
            verify(frRepository, never()).save(any(Fr.class));
        }

        @Test
        void testWriteThrowsExceptionOnError() {
            // Given
            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("Database error"));

            // When/Then
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(testData));
            assertThatThrownBy(() -> writer.write(chunk))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Database error");
        }
    }

    @Nested
    class ImportoVerificationTests {

        @Test
        void testVerificaImportoMismatch() {
            // Given - payment with different amount
            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .codDominio("12345678901")
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1)
                .importoPagato(50.00) // Different amount
                .singoloVersamento(sv)
                .build();

            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(testData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            assertThat(savedFr.getRendicontazioni().get(0).getAnomalie()).contains("007104");
        }

        @Test
        void testVerificaImportoRevocaMatch() {
            // Given - payment with revoked status
            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1L)
                .importoPagato(-100.00) // Negative for revoca
                .esito(Costanti.PAYMENT_REVOKED)
                .data(Instant.now())
                .build());

            FdrPaymentsProcessor.FdrCompleteData revokedData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-002")
                .iur("IUR-FLUSSO")
                .dataOraFlusso(Instant.now())
                .dataRegolamento(Instant.now())
                .numeroPagamenti(1L)
                .importoTotalePagamenti(-100.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .codDominio("12345678901")
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1)
                .importoPagato(100.00)
                .importoRevocato(100.00)
                .singoloVersamento(sv)
                .build();

            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(revokedData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            // Revoca should match, no anomaly for amount
            assertThat(savedFr.getRendicontazioni().get(0).getAnomalie()).isNull();
        }

        @Test
        void testVerificaImportoRevocaMismatch() {
            // Given - revoked payment with wrong amount
            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1L)
                .importoPagato(-50.00) // Different from revoked amount
                .esito(Costanti.PAYMENT_REVOKED)
                .data(Instant.now())
                .build());

            FdrPaymentsProcessor.FdrCompleteData revokedData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-003")
                .iur("IUR-FLUSSO")
                .numeroPagamenti(1L)
                .importoTotalePagamenti(-50.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .codDominio("12345678901")
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1)
                .importoPagato(100.00)
                .importoRevocato(100.00) // Different from rendicontazione
                .singoloVersamento(sv)
                .build();

            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(revokedData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            assertThat(savedFr.getRendicontazioni().get(0).getAnomalie()).contains("007112");
        }

        @Test
        void testVerificaImportoPagatoNull() {
            // Given - payment with null importoPagato
            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .codDominio("12345678901")
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1)
                .importoPagato(null) // null importo
                .singoloVersamento(sv)
                .build();

            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(testData));
            writer.write(chunk);

            // Then - no exception, returns early
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            // Should not have amount mismatch anomaly since we returned early
            assertThat(savedFr.getRendicontazioni().get(0).getAnomalie()).isNull();
        }

        @Test
        void testVerificaImportoRevocatoNull() {
            // Given - revoked payment with null importoRevocato
            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1L)
                .importoPagato(-100.00)
                .esito(Costanti.PAYMENT_REVOKED)
                .data(Instant.now())
                .build());

            FdrPaymentsProcessor.FdrCompleteData revokedData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-004")
                .numeroPagamenti(1L)
                .importoTotalePagamenti(-100.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .codDominio("12345678901")
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1)
                .importoPagato(100.00)
                .importoRevocato(null) // null revocato
                .singoloVersamento(sv)
                .build();

            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(revokedData));
            writer.write(chunk);

            // Then - no exception, returns early
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            // Should not have amount mismatch anomaly since we returned early
            assertThat(savedFr.getRendicontazioni().get(0).getAnomalie()).isNull();
        }
    }

    @Nested
    class PaymentNotFoundTests {

        @Test
        void testPaymentNotFoundWithIuvEsterno() {
            // Given - IUV not internal to dominio (will be marked as altro intermediario)
            Dominio domAuxDigit3 = Dominio.builder()
                .id(1L)
                .codDominio("12345678901")
                .auxDigit(3)
                .segregationCode(50)
                .scaricaFr(true)
                .build();

            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("EXTERNAL123456789") // Not internal IUV
                .iur("IUR001")
                .indiceDati(1L)
                .importoPagato(100.00)
                .esito(Costanti.PAYMENT_EXECUTED)
                .data(Instant.now())
                .build());

            FdrPaymentsProcessor.FdrCompleteData externalIuvData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-005")
                .numeroPagamenti(1L)
                .importoTotalePagamenti(100.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(domAuxDigit3));
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(Collections.emptyList());

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(externalIuvData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            assertThat(savedFr.getRendicontazioni().get(0).getStato())
                .isEqualTo(Costanti.RENDICONTAZIONE_STATO_ALTRO_INTERMEDIARIO);
        }

        @Test
        void testPaymentNotFoundWithIuvInternoSenzaRPT() {
            // Given - internal IUV with NO_RPT status
            Dominio domAuxDigit0 = Dominio.builder()
                .id(1L)
                .codDominio("12345678901")
                .auxDigit(0)
                .segregationCode(null)
                .scaricaFr(true)
                .build();

            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("123456789012345") // 15 digits = internal for auxDigit 0
                .iur("IUR001")
                .indiceDati(1L)
                .importoPagato(100.00)
                .esito(Costanti.PAYMENT_NO_RPT)
                .data(Instant.now())
                .build());

            FdrPaymentsProcessor.FdrCompleteData noRptData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-006")
                .numeroPagamenti(1L)
                .importoTotalePagamenti(100.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(domAuxDigit0));
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(Collections.emptyList());
            when(versamentoRepository.findByDominioCodDominioAndIuvPagamento(anyString(), anyString()))
                .thenReturn(Optional.empty());

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(noRptData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            // Should have anomaly for unknown versamento
            assertThat(savedFr.getRendicontazioni().get(0).getAnomalie()).contains("007111");
        }

        @Test
        void testPaymentNotFoundWithIuvInternoStandInNoRpt() {
            // Given - internal IUV with STAND_IN_NO_RPT status and versamento with multiple singoli
            Dominio domAuxDigit0 = Dominio.builder()
                .id(1L)
                .codDominio("12345678901")
                .auxDigit(0)
                .segregationCode(null)
                .scaricaFr(true)
                .build();

            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("123456789012345")
                .iur("IUR001")
                .indiceDati(1L)
                .importoPagato(100.00)
                .esito(Costanti.PAYMENT_STAND_IN_NO_RPT)
                .data(Instant.now())
                .build());

            FdrPaymentsProcessor.FdrCompleteData standInNoRptData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-007")
                .numeroPagamenti(1L)
                .importoTotalePagamenti(100.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(domAuxDigit0));
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(Collections.emptyList());

            Applicazione app = Applicazione.builder().id(1L).codApplicazione("APP001").build();
            Versamento versamento = Versamento.builder()
                .id(1L)
                .codVersamentoEnte("VERS001")
                .statoVersamento("NON_ESEGUITO")
                .applicazione(app)
                .dominio(domAuxDigit0)
                .build();
            when(versamentoRepository.findByDominioCodDominioAndIuvPagamento(anyString(), anyString()))
                .thenReturn(Optional.of(versamento));

            // Multiple singoli versamenti = anomalia
            Set<SingoloVersamento> singoli = new HashSet<>();
            singoli.add(SingoloVersamento.builder().id(1L).indiceDati(1).build());
            singoli.add(SingoloVersamento.builder().id(2L).indiceDati(2).build());
            when(singoloVersamentoRepository.findAllByVersamentoId(1L)).thenReturn(singoli);

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(standInNoRptData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            // Should have anomaly for multiple singoli versamenti
            assertThat(savedFr.getRendicontazioni().get(0).getAnomalie()).contains("007114");
        }

        @Test
        void testMultiplePagamentiDuplicati() {
            // Given - multiple payments found for same IUV/IUR
            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            Pagamento pag1 = Pagamento.builder().id(1L).build();
            Pagamento pag2 = Pagamento.builder().id(2L).build();
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(List.of(pag1, pag2));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(testData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            assertThat(savedFr.getRendicontazioni().get(0).getAnomalie()).contains("007102");
            assertThat(savedFr.getDescrizioneStato()).contains("007102");
            assertThat(savedFr.getStato()).isEqualTo(Costanti.FLUSSO_STATO_ANOMALA);
        }
    }

    @Nested
    class QuadraturaTests {

        @Test
        void testQuadraturaImportoMismatch() {
            // Given - total amount mismatch
            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1L)
                .importoPagato(100.00)
                .esito(Costanti.PAYMENT_EXECUTED)
                .build());

            FdrPaymentsProcessor.FdrCompleteData mismatchData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-008")
                .numeroPagamenti(1L)
                .importoTotalePagamenti(200.00) // Should be 100
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .importoPagato(100.00)
                .singoloVersamento(sv)
                .build();
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(mismatchData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            assertThat(savedFr.getDescrizioneStato()).contains("007106");
            assertThat(savedFr.getStato()).isEqualTo(Costanti.FLUSSO_STATO_ANOMALA);
        }

        @Test
        void testQuadraturaNumeroPagamentiMismatch() {
            // Given - payment count mismatch
            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1L)
                .importoPagato(100.00)
                .esito(Costanti.PAYMENT_EXECUTED)
                .build());

            FdrPaymentsProcessor.FdrCompleteData mismatchData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-009")
                .numeroPagamenti(5L) // Should be 1
                .importoTotalePagamenti(100.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .importoPagato(100.00)
                .singoloVersamento(sv)
                .build();
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(mismatchData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            assertThat(savedFr.getDescrizioneStato()).contains("007107");
            assertThat(savedFr.getStato()).isEqualTo(Costanti.FLUSSO_STATO_ANOMALA);
        }

        @Test
        void testQuadraturaWithNullValues() {
            // Given - null importoTotalePagamenti and numeroPagamenti
            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1L)
                .importoPagato(100.00)
                .esito(Costanti.PAYMENT_EXECUTED)
                .build());

            FdrPaymentsProcessor.FdrCompleteData nullData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-010")
                .numeroPagamenti(null)
                .importoTotalePagamenti(null)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .importoPagato(100.00)
                .singoloVersamento(sv)
                .build();
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(nullData));
            writer.write(chunk);

            // Then - should not fail, quadratura checks are skipped
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            assertThat(savedFr.getStato()).isEqualTo(Costanti.FLUSSO_STATO_ACCETTATA);
        }
    }

    @Nested
    class RendicontazioneDuplicataTests {

        @Test
        void testRendicontazioneDuplicataInFlusso() {
            // Given - two payments with same IUV/IUR/indiceDati in same flow
            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1L)
                .importoPagato(50.00)
                .esito(Costanti.PAYMENT_EXECUTED)
                .build());
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(1L) // Same as first
                .importoPagato(50.00)
                .esito(Costanti.PAYMENT_EXECUTED)
                .build());

            FdrPaymentsProcessor.FdrCompleteData dupData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-011")
                .numeroPagamenti(2L)
                .importoTotalePagamenti(100.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .importoPagato(50.00)
                .singoloVersamento(sv)
                .build();
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(dupData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            // Second rendicontazione should have duplicate anomaly
            assertThat(savedFr.getRendicontazioni()).hasSize(2);
            assertThat(savedFr.getRendicontazioni().get(1).getAnomalie()).contains("007115");
        }
    }

    @Nested
    class FindPagamentiVariantsTests {

        @Test
        void testFindPagamentiWithNullIur() {
            // Given - payment data without IUR
            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("IUV001")
                .iur(null) // null IUR
                .indiceDati(1L)
                .importoPagato(100.00)
                .esito(Costanti.PAYMENT_EXECUTED)
                .build());

            FdrPaymentsProcessor.FdrCompleteData nullIurData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-012")
                .numeroPagamenti(1L)
                .importoTotalePagamenti(100.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .importoPagato(100.00)
                .singoloVersamento(sv)
                .build();
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIndiceDati(eq("12345678901"), eq("IUV001"), eq(1L)))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(nullIurData));
            writer.write(chunk);

            // Then
            verify(pagamentoRepository).findAllByCodDominioAndIuvAndIndiceDati("12345678901", "IUV001", 1L);
            verify(frRepository).save(any(Fr.class));
        }

        @Test
        void testFindPagamentiWithNullIndiceDati() {
            // Given - payment data without indiceDati
            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("IUV001")
                .iur("IUR001")
                .indiceDati(null) // null indiceDati
                .importoPagato(100.00)
                .esito(Costanti.PAYMENT_EXECUTED)
                .build());

            FdrPaymentsProcessor.FdrCompleteData nullIndiceData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-013")
                .numeroPagamenti(1L)
                .importoTotalePagamenti(100.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .importoPagato(100.00)
                .singoloVersamento(sv)
                .build();
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIur(eq("12345678901"), eq("IUV001"), eq("IUR001")))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(nullIndiceData));
            writer.write(chunk);

            // Then
            verify(pagamentoRepository).findAllByCodDominioAndIuvAndIur("12345678901", "IUV001", "IUR001");
            verify(frRepository).save(any(Fr.class));
        }

        @Test
        void testFindPagamentiWithNullIurAndIndiceDati() {
            // Given - payment data without IUR and indiceDati
            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("IUV001")
                .iur(null)
                .indiceDati(null)
                .importoPagato(100.00)
                .esito(Costanti.PAYMENT_EXECUTED)
                .build());

            FdrPaymentsProcessor.FdrCompleteData minimalData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-014")
                .numeroPagamenti(1L)
                .importoTotalePagamenti(100.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .importoPagato(100.00)
                .singoloVersamento(sv)
                .build();
            when(pagamentoRepository.findAllByCodDominioAndIuv(eq("12345678901"), eq("IUV001")))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(minimalData));
            writer.write(chunk);

            // Then
            verify(pagamentoRepository).findAllByCodDominioAndIuv("12345678901", "IUV001");
            verify(frRepository).save(any(Fr.class));
        }
    }

    @Nested
    class VersamentoAssociazioneTests {

        @Test
        void testAssociazioneVersamentoConSingoloVersamento() {
            // Given - payment not found, but versamento exists with matching singolo versamento
            Dominio domAuxDigit0 = Dominio.builder()
                .id(1L)
                .codDominio("12345678901")
                .auxDigit(0)
                .build();

            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("123456789012345") // Internal IUV for auxDigit 0
                .iur("IUR001")
                .indiceDati(2L)
                .importoPagato(100.00)
                .esito(Costanti.PAYMENT_EXECUTED)
                .build());

            FdrPaymentsProcessor.FdrCompleteData versData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-015")
                .numeroPagamenti(1L)
                .importoTotalePagamenti(100.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(domAuxDigit0));
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(Collections.emptyList());

            Applicazione app = Applicazione.builder().id(1L).codApplicazione("APP001").build();
            Versamento versamento = Versamento.builder()
                .id(1L)
                .codVersamentoEnte("VERS001")
                .statoVersamento("NON_ESEGUITO")
                .applicazione(app)
                .dominio(domAuxDigit0)
                .build();
            when(versamentoRepository.findByDominioCodDominioAndIuvPagamento(anyString(), anyString()))
                .thenReturn(Optional.of(versamento));

            SingoloVersamento sv1 = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            SingoloVersamento sv2 = SingoloVersamento.builder().id(2L).indiceDati(2).build();
            Set<SingoloVersamento> singoli = new HashSet<>();
            singoli.add(sv1);
            singoli.add(sv2);
            when(singoloVersamentoRepository.findAllByVersamentoId(1L)).thenReturn(singoli);

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(versData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            // Rendicontazione should have sv2 associated (indiceDati=2)
            assertThat(savedFr.getRendicontazioni().get(0).getSingoloVersamento()).isEqualTo(sv2);
            // Should have anomaly for missing pagamento
            assertThat(savedFr.getRendicontazioni().get(0).getAnomalie()).contains("007101");
        }

        @Test
        void testAssociazioneVersamentoConIndiceDatiNull() {
            // Given - payment not found, versamento exists, indiceDati is null (defaults to 1)
            Dominio domAuxDigit0 = Dominio.builder()
                .id(1L)
                .codDominio("12345678901")
                .auxDigit(0)
                .build();

            List<FdrPaymentsProcessor.PaymentData> payments = new ArrayList<>();
            payments.add(FdrPaymentsProcessor.PaymentData.builder()
                .iuv("123456789012345")
                .iur("IUR001")
                .indiceDati(null) // null, should default to 1
                .importoPagato(100.00)
                .esito(Costanti.PAYMENT_EXECUTED)
                .build());

            FdrPaymentsProcessor.FdrCompleteData versData = FdrPaymentsProcessor.FdrCompleteData.builder()
                .frTempId(1L)
                .codPsp("PSP001")
                .codDominio("12345678901")
                .codFlusso("FDR-TEST-016")
                .numeroPagamenti(1L)
                .importoTotalePagamenti(100.00)
                .revisione(1L)
                .payments(payments)
                .build();

            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(domAuxDigit0));
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIur(anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

            Applicazione app = Applicazione.builder().id(1L).codApplicazione("APP001").build();
            Versamento versamento = Versamento.builder()
                .id(1L)
                .codVersamentoEnte("VERS001")
                .statoVersamento("NON_ESEGUITO")
                .applicazione(app)
                .dominio(domAuxDigit0)
                .build();
            when(versamentoRepository.findByDominioCodDominioAndIuvPagamento(anyString(), anyString()))
                .thenReturn(Optional.of(versamento));

            SingoloVersamento sv1 = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Set<SingoloVersamento> singoli = new HashSet<>();
            singoli.add(sv1);
            when(singoloVersamentoRepository.findAllByVersamentoId(1L)).thenReturn(singoli);

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            FrTemp frTemp = FrTemp.builder().id(1L).build();
            when(frTempRepository.findById(1L)).thenReturn(Optional.of(frTemp));

            // When
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(versData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(frCaptor.capture());
            Fr savedFr = frCaptor.getValue();
            // Rendicontazione should have sv1 associated (indiceDati defaults to 1)
            assertThat(savedFr.getRendicontazioni().get(0).getSingoloVersamento()).isEqualTo(sv1);
        }
    }

    @Nested
    class FrTempDeletionTests {

        @Test
        void testFrTempNotFoundDoesNotThrow() {
            // Given
            when(frRepository.findByCodFlussoAndCodPspAndRevisione(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
            when(dominioRepository.findByCodDominio("12345678901"))
                .thenReturn(Optional.of(testDominio));

            SingoloVersamento sv = SingoloVersamento.builder().id(1L).indiceDati(1).build();
            Pagamento pagamento = Pagamento.builder()
                .id(1L)
                .importoPagato(100.00)
                .singoloVersamento(sv)
                .build();
            when(pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(List.of(pagamento));

            when(frRepository.save(any(Fr.class))).thenAnswer(inv -> {
                Fr fr = inv.getArgument(0);
                fr.setId(1L);
                return fr;
            });

            // FrTemp not found
            when(frTempRepository.findById(1L)).thenReturn(Optional.empty());

            // When - should not throw
            Chunk<FdrPaymentsProcessor.FdrCompleteData> chunk = new Chunk<>(List.of(testData));
            writer.write(chunk);

            // Then
            verify(frRepository).save(any(Fr.class));
            verify(frTempRepository, never()).delete(any(FrTemp.class));
        }
    }
}
