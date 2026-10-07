package it.govpay.fdr.batch.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.fdr.batch.config.FdrApiClientConfig;
import it.govpay.fdr.batch.dto.FdrClaimedFile;
import it.govpay.fdr.batch.dto.FdrFileItem;
import it.govpay.fdr.batch.repository.FrRepository;
import it.govpay.fdr.batch.step4.FdrPaymentsProcessor;

@DisplayName("FdrFileSystemProcessor Tests")
class FdrFileSystemProcessorTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Rome");
    private static final String COD_DOMINIO = "09788660968";
    private static final String COD_FLUSSO = "2026-08-06ABI03365-B6FXC00000004719";
    private static final String COD_PSP = "ABI03365";

    /**
     * Flusso reale scaricato dalle API pagoPA: metadati della GET puntuale con i
     * pagamenti inline. Contiene solo computedTotPayments/computedSumPayments, senza
     * i corrispondenti totPayments/sumPayments.
     */
    private static final String FLUSSO_JSON = """
        {"fdr":"2026-08-06ABI03365-B6FXC00000004719","fdrDate":1786109246.000000000,"revision":1,
         "created":1786109996.570225000,"updated":1786109996.592019000,"published":1786109996.592019000,
         "status":"PUBLISHED",
         "sender":{"type":"BIC_CODE","id":"ABI03365","pspName":"CHERRY BANK SPA","pspBrokerId":"97249640588",
                   "channelId":"97249640588_01","password":"PLACEHOLDER","pspId":"ABI03365"},
         "receiver":{"id":"09788660968","organizationName":"RIENERGIA SRLS","organizationId":"09788660968"},
         "regulation":"Bonifico SEPA-03365-B6FXC","regulationDate":"2026-08-06",
         "computedTotPayments":1,"computedSumPayments":70.43,
         "payments":[{"index":1,"iuv":"00551000024916517","iur":"8c1d4c6cf55a42108cc8820456031b0f",
                      "pay":70.43,"payDate":"2026-08-06T00:00:00Z","payStatus":"EXECUTED","idTransfer":1}]}
        """;

    @Mock
    private DominioRepository dominioRepository;

    @Mock
    private FrRepository frRepository;

    @TempDir
    Path tempDir;

    private FdrFileSystemProcessor processor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        FdrApiClientConfig apiClientConfig = new FdrApiClientConfig();
        ReflectionTestUtils.setField(apiClientConfig, "timezone", "Europe/Rome");

        processor = new FdrFileSystemProcessor(
            dominioRepository,
            frRepository,
            new FdrPaymentsProcessor(null, ZONE_ID),
            ZONE_ID,
            apiClientConfig);

        dominioAbilitato();
        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(
            anyString(), anyString(), anyString(), anyLong())).thenReturn(false);
    }

    private void dominioAbilitato() {
        when(dominioRepository.findByCodDominio(any())).thenReturn(Optional.of(
            DominioEntity.builder()
                .codDominio(COD_DOMINIO)
                .abilitato(true)
                .ragioneSociale("RIENERGIA SRLS")
                .auxDigit(0)
                .intermediato(true)
                .scaricaFr(true)
                .build()));
    }

    private FdrClaimedFile scrivi(String contenuto) throws IOException {
        Path file = tempDir.resolve("flusso.json.node.processing");
        Files.writeString(file, contenuto);
        return new FdrClaimedFile(file, "flusso.json");
    }

    @Test
    @DisplayName("Un flusso valido viene mappato sugli stessi dati prodotti dal canale API")
    void flussoValido() throws IOException {
        FdrFileItem item = processor.process(scrivi(FLUSSO_JSON));

        assertThat(item.esito()).isEqualTo(FdrFileItem.Esito.ACQUISIRE);
        assertThat(item.data()).isNotNull();
        assertThat(item.data().getFrTempId()).isNull();
        assertThat(item.data().getCodDominio()).isEqualTo(COD_DOMINIO);
        assertThat(item.data().getCodFlusso()).isEqualTo(COD_FLUSSO);
        assertThat(item.data().getCodPsp()).isEqualTo(COD_PSP);
        assertThat(item.data().getRevisione()).isEqualTo(1L);
        assertThat(item.data().getIur()).isEqualTo("Bonifico SEPA-03365-B6FXC");
        assertThat(item.data().getRagioneSocialePsp()).isEqualTo("CHERRY BANK SPA");
        assertThat(item.data().getRagioneSocialeDominio()).isEqualTo("RIENERGIA SRLS");
        assertThat(item.data().getStato()).isEqualTo("PUBLISHED");
        // fdrDate 1786109246 = 2026-08-07T13:27:26Z, ovvero le 15:27:26 a Roma
        assertThat(item.data().getDataOraFlusso()).isEqualTo(LocalDateTime.of(2026, 8, 7, 15, 27, 26));
        assertThat(item.data().getDataRegolamento()).isEqualTo(LocalDateTime.of(2026, 8, 6, 0, 0));
    }

    @Test
    @DisplayName("In assenza di totPayments/sumPayments si usano i valori computed")
    void fallbackSuiTotaliComputed() throws IOException {
        FdrFileItem item = processor.process(scrivi(FLUSSO_JSON));

        assertThat(item.data().getNumeroPagamenti()).isEqualTo(1L);
        assertThat(item.data().getImportoTotalePagamenti()).isEqualTo(70.43);
    }

    @Test
    @DisplayName("Se presenti, totPayments e sumPayments prevalgono sui valori computed")
    void totaliDichiaratiPrevalgono() throws IOException {
        FdrFileItem item = processor.process(scrivi(FLUSSO_JSON.replace(
            "\"computedTotPayments\":1,\"computedSumPayments\":70.43",
            "\"computedTotPayments\":1,\"computedSumPayments\":70.43,\"totPayments\":2,\"sumPayments\":99.99")));

        assertThat(item.data().getNumeroPagamenti()).isEqualTo(2L);
        assertThat(item.data().getImportoTotalePagamenti()).isEqualTo(99.99);
    }

    @Test
    @DisplayName("I pagamenti sono convertiti con la stessa codifica dello step 4")
    void pagamentiConvertiti() throws IOException {
        FdrFileItem item = processor.process(scrivi(FLUSSO_JSON));

        assertThat(item.data().getPayments()).hasSize(1);
        FdrPaymentsProcessor.PaymentData pagamento = item.data().getPayments().get(0);
        assertThat(pagamento.getIuv()).isEqualTo("00551000024916517");
        assertThat(pagamento.getIur()).isEqualTo("8c1d4c6cf55a42108cc8820456031b0f");
        assertThat(pagamento.getIndiceDati()).isEqualTo(1L);
        assertThat(pagamento.getImportoPagato()).isEqualTo(70.43);
        assertThat(pagamento.getEsito()).isZero(); // EXECUTED
    }

    @Test
    @DisplayName("Un file non parsabile viene scartato senza sollevare eccezioni")
    void fileMalformato() throws IOException {
        FdrFileItem item = processor.process(scrivi("{ questo non e' json"));

        assertThat(item.esito()).isEqualTo(FdrFileItem.Esito.SCARTATO);
        assertThat(item.motivo()).contains("Parsing del file fallito");
    }

    @Test
    @DisplayName("Un file senza la lista payments non e' nel formato atteso")
    void senzaPagamenti() throws IOException {
        FdrFileItem item = processor.process(scrivi(FLUSSO_JSON.replace("\"payments\"", "\"altro\"")));

        assertThat(item.esito()).isEqualTo(FdrFileItem.Esito.SCARTATO);
        assertThat(item.motivo()).contains("payments");
    }

    @Test
    @DisplayName("Un file senza identificativo del dominio viene scartato")
    void senzaDominio() throws IOException {
        FdrFileItem item = processor.process(scrivi(FLUSSO_JSON.replace("\"organizationId\":\"09788660968\"",
            "\"organizationId\":null")));

        assertThat(item.esito()).isEqualTo(FdrFileItem.Esito.SCARTATO);
        assertThat(item.motivo()).contains("receiver.organizationId");
    }

    @Test
    @DisplayName("Un dominio non censito viene scartato")
    void dominioNonCensito() throws IOException {
        when(dominioRepository.findByCodDominio(any())).thenReturn(Optional.empty());

        FdrFileItem item = processor.process(scrivi(FLUSSO_JSON));

        assertThat(item.esito()).isEqualTo(FdrFileItem.Esito.SCARTATO);
        assertThat(item.motivo()).contains("non censito");
    }

    @Test
    @DisplayName("Un dominio disabilitato viene scartato")
    void dominioDisabilitato() throws IOException {
        when(dominioRepository.findByCodDominio(any())).thenReturn(Optional.of(
            DominioEntity.builder()
                .codDominio(COD_DOMINIO)
                .abilitato(false)
                .ragioneSociale("RIENERGIA SRLS")
                .auxDigit(0)
                .intermediato(true)
                .scaricaFr(true)
                .build()));

        FdrFileItem item = processor.process(scrivi(FLUSSO_JSON));

        assertThat(item.esito()).isEqualTo(FdrFileItem.Esito.SCARTATO);
        assertThat(item.motivo()).contains("non abilitato");
    }

    @Test
    @DisplayName("Un flusso gia' presente in FR non viene reinserito")
    void flussoGiaAcquisito() throws IOException {
        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(
            COD_DOMINIO, COD_FLUSSO, COD_PSP, 1L)).thenReturn(true);

        FdrFileItem item = processor.process(scrivi(FLUSSO_JSON));

        assertThat(item.esito()).isEqualTo(FdrFileItem.Esito.DUPLICATO);
        assertThat(item.data()).isNull();
        assertThat(item.motivo()).contains("gia' presente in FR");
    }
}
