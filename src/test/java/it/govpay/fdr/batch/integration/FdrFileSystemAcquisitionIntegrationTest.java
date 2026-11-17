package it.govpay.fdr.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.fdr.batch.config.FdrInputProperties;
import it.govpay.fdr.batch.dto.FdrClaimedFile;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.entity.Rendicontazione;
import it.govpay.fdr.batch.filesystem.FdrFileSystemProcessor;
import it.govpay.fdr.batch.filesystem.FdrFileSystemReader;
import it.govpay.fdr.batch.filesystem.FdrFileSystemWriter;
import it.govpay.fdr.batch.gde.service.GdeService;
import it.govpay.fdr.batch.repository.FdrDominioRepository;
import it.govpay.fdr.batch.repository.FrRepository;
import it.govpay.fdr.batch.repository.RendicontazioneRepository;
import it.govpay.fdr.batch.service.FdrApiService;

/**
 * Test di integrazione dell'acquisizione di un flusso depositato su file system:
 * dal file JSON fino alle righe su FR e RENDICONTAZIONI, senza passare dalle API pagoPA.
 * <p>
 * Il test non e' transazionale di proposito: la persistenza avviene in una transazione
 * {@code REQUIRES_NEW}, che non vedrebbe i dati di una transazione di test non committata.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.batch.job.enabled=false",
})
class FdrFileSystemAcquisitionIntegrationTest {

    private static final String COD_DOMINIO = "09788660968";
    private static final String COD_FLUSSO = "2026-08-06ABI03365-B6FXC00000004719";
    private static final String COD_PSP = "ABI03365";

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

    @TempDir
    Path inputDir;

    @Autowired
    private FdrInputProperties inputProperties;

    @Autowired
    private DominioRepository dominioRepository;

    @Autowired
    private StazioneRepository stazioneRepository;

    @Autowired
    private FrRepository frRepository;

    @Autowired
    private FdrDominioRepository fdrDominioRepository;

    @Autowired
    private RendicontazioneRepository rendicontazioneRepository;

    @Autowired
    private FdrFileSystemProcessor processor;

    @Autowired
    private FdrFileSystemWriter writer;

    @MockitoBean
    private FdrApiService fdrApiService;

    @MockitoBean
    private GdeService gdeService;

    private DominioEntity dominio;
    private FdrInputProperties configurazioneOriginale;

    @BeforeEach
    void setUp() {
        // Le proprieta' sono un singleton condiviso con gli altri test dello stesso
        // contesto: si punta alla directory temporanea e si ripristina dopo il test.
        configurazioneOriginale = copiaConfigurazione();
        inputProperties.setEnabled(true);
        inputProperties.setDir(inputDir.toString());
        inputProperties.setProcessedDir(null);
        inputProperties.setErrorDir(null);
        inputProperties.setMaxFilesPerRun(1000);

        StazioneEntity stazione = stazioneRepository.findByCodStazione("12345678901_01").orElseThrow();
        dominio = dominioRepository.save(DominioEntity.builder()
            .codDominio(COD_DOMINIO)
            .auxDigit(0)
            .abilitato(true)
            .ragioneSociale("RIENERGIA SRLS")
            .intermediato(true)
            .scaricaFr(true)
            .stazione(stazione)
            .build());
    }

    @AfterEach
    void tearDown() {
        rendicontazioneRepository.deleteAll();
        frRepository.deleteAll();
        dominioRepository.delete(dominio);
        ripristinaConfigurazione();
    }

    private FdrInputProperties copiaConfigurazione() {
        FdrInputProperties copia = new FdrInputProperties();
        copia.setEnabled(inputProperties.isEnabled());
        copia.setDir(inputProperties.getDir());
        copia.setProcessedDir(inputProperties.getProcessedDir());
        copia.setErrorDir(inputProperties.getErrorDir());
        copia.setExtension(inputProperties.getExtension());
        copia.setMaxFilesPerRun(inputProperties.getMaxFilesPerRun());
        return copia;
    }

    private void ripristinaConfigurazione() {
        inputProperties.setEnabled(configurazioneOriginale.isEnabled());
        inputProperties.setDir(configurazioneOriginale.getDir());
        inputProperties.setProcessedDir(configurazioneOriginale.getProcessedDir());
        inputProperties.setErrorDir(configurazioneOriginale.getErrorDir());
        inputProperties.setExtension(configurazioneOriginale.getExtension());
        inputProperties.setMaxFilesPerRun(configurazioneOriginale.getMaxFilesPerRun());
    }

    private void deposita(String nome, String contenuto) throws IOException {
        Files.writeString(inputDir.resolve(nome), contenuto);
    }

    /**
     * Esegue lo step come lo eseguirebbe Spring Batch con chunk di 1.
     */
    private void eseguiStep() {
        FdrFileSystemReader reader = new FdrFileSystemReader(inputProperties, "test-node");
        reader.open(new ExecutionContext());

        FdrClaimedFile claimed;
        while ((claimed = reader.read()) != null) {
            writer.write(new Chunk<>(List.of(processor.process(claimed))));
        }
        reader.close();
    }

    @Test
    @DisplayName("Un flusso valido depositato su file system finisce su FR e RENDICONTAZIONI")
    void acquisisceIlFlusso() throws IOException {
        deposita("flusso.json", FLUSSO_JSON);

        eseguiStep();

        List<Fr> flussi = frRepository.findAll();
        assertThat(flussi).hasSize(1);
        Fr fr = flussi.get(0);
        assertThat(fr.getCodDominio()).isEqualTo(COD_DOMINIO);
        assertThat(fr.getCodFlusso()).isEqualTo(COD_FLUSSO);
        assertThat(fr.getCodPsp()).isEqualTo(COD_PSP);
        assertThat(fr.getRevisione()).isEqualTo(1L);
        assertThat(fr.getIur()).isEqualTo("Bonifico SEPA-03365-B6FXC");
        assertThat(fr.getNumeroPagamenti()).isEqualTo(1L);
        assertThat(fr.getImportoTotalePagamenti()).isEqualTo(70.43);
        assertThat(fr.getRagioneSocialePsp()).isEqualTo("CHERRY BANK SPA");
        assertThat(fr.getRagioneSocialeDominio()).isEqualTo("RIENERGIA SRLS");
        assertThat(fr.getDataOraFlusso()).isEqualTo(LocalDateTime.of(2026, 8, 7, 15, 27, 26));

        List<Rendicontazione> rendicontazioni = rendicontazioneRepository.findAll();
        assertThat(rendicontazioni).hasSize(1);
        assertThat(rendicontazioni.get(0).getIuv()).isEqualTo("00551000024916517");
        assertThat(rendicontazioni.get(0).getIur()).isEqualTo("8c1d4c6cf55a42108cc8820456031b0f");
        assertThat(rendicontazioni.get(0).getImportoPagato()).isEqualTo(70.43);
        assertThat(rendicontazioni.get(0).getEsito()).isZero();

        assertThat(inputProperties.getProcessedDirPath().resolve("flusso.json")).exists();
        assertThat(inputDir.resolve("flusso.json")).doesNotExist();
    }

    @Test
    @DisplayName("Lo stesso flusso ricaricato non viene duplicato")
    void nonDuplicaUnFlussoGiaAcquisito() throws IOException {
        deposita("flusso.json", FLUSSO_JSON);
        eseguiStep();

        deposita("flusso-bis.json", FLUSSO_JSON);
        eseguiStep();

        assertThat(frRepository.findAll()).hasSize(1);
        assertThat(rendicontazioneRepository.findAll()).hasSize(1);
        assertThat(inputProperties.getProcessedDirPath().resolve("flusso-bis.json")).exists();
    }

    @Test
    @DisplayName("Un file malformato finisce fra gli scarti e non blocca gli altri")
    void scartaIlFileMalformatoSenzaBloccareGliAltri() throws IOException {
        deposita("a-rotto.json", "{ non json");
        deposita("b-valido.json", FLUSSO_JSON);

        eseguiStep();

        assertThat(frRepository.findAll()).hasSize(1);
        assertThat(inputProperties.getErrorDirPath().resolve("a-rotto.json")).exists();
        assertThat(inputProperties.getErrorDirPath().resolve("a-rotto.json.error.txt"))
            .exists()
            .content().contains("Parsing del file fallito");
        assertThat(inputProperties.getProcessedDirPath().resolve("b-valido.json")).exists();
    }

    @Test
    @DisplayName("Senza directory configurata lo step non fa nulla e non fallisce")
    void directoryNonConfigurata() {
        inputProperties.setDir(null);

        eseguiStep();

        assertThat(frRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Una directory configurata ma inesistente non viene creata ne' fa fallire lo step")
    void directoryInesistente() {
        Path inesistente = inputDir.resolve("mai-creata");
        inputProperties.setDir(inesistente.toString());

        eseguiStep();

        assertThat(frRepository.findAll()).isEmpty();
        assertThat(inesistente).doesNotExist();
    }

    @Test
    @DisplayName("Un flusso di dominio non censito viene scartato senza toccare il database")
    void scartaIlDominioNonCensito() throws IOException {
        deposita("flusso.json", FLUSSO_JSON.replace(COD_DOMINIO, "00000000000"));

        eseguiStep();

        assertThat(frRepository.findAll()).isEmpty();
        assertThat(inputProperties.getErrorDirPath().resolve("flusso.json.error.txt"))
            .exists()
            .content().contains("non censito");
    }

    // ---- Flussi XML ctFlussoRiversamento -------------------------------------------

    private static final String COD_FLUSSO_XML = "2026-07-15ABI03365-B6FXC00000004719";

    /** Finche' il default resta {@code .json} gli XML vanno abilitati esplicitamente. */
    private void depositaXml(String nome, String contenuto) throws IOException {
        inputProperties.setExtension(".xml");
        deposita(nome, contenuto);
    }

    private static String flussoXml() throws IOException {
        try (InputStream in = FdrFileSystemAcquisitionIntegrationTest.class
                .getResourceAsStream("/fdr-xml/flusso-valido.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Un flusso XML finisce su FR e RENDICONTAZIONI con revisione 1 e senza spostare il watermark")
    void acquisisceIlFlussoXml() throws IOException {
        depositaXml("flusso.xml", flussoXml());

        eseguiStep();

        List<Fr> flussi = frRepository.findAll();
        assertThat(flussi).hasSize(1);
        Fr fr = flussi.get(0);
        assertThat(fr.getCodDominio()).isEqualTo(COD_DOMINIO);
        assertThat(fr.getCodFlusso()).isEqualTo(COD_FLUSSO_XML);
        assertThat(fr.getCodPsp()).isEqualTo(COD_PSP);
        assertThat(fr.getRevisione()).isEqualTo(1L);
        assertThat(fr.getObsoleto()).isFalse();
        assertThat(fr.getDataOraFlusso()).isEqualTo(LocalDateTime.of(2026, 7, 15, 10, 30));
        assertThat(fr.getDataRegolamento()).isEqualTo(LocalDateTime.of(2026, 7, 16, 0, 0));
        assertThat(fr.getNumeroPagamenti()).isEqualTo(3L);
        assertThat(fr.getImportoTotalePagamenti()).isEqualTo(170.43);
        assertThat(fr.getCodBicRiversamento()).isEqualTo("CHBKITMM");
        assertThat(fr.getDataOraPubblicazione()).isNull();

        assertThat(rendicontazioneRepository.findAll())
            .extracting(Rendicontazione::getEsito)
            .containsExactlyInAnyOrder(0, 4, 8);
        assertThat(inputProperties.getProcessedDirPath().resolve("flusso.xml")).exists();

        // Un flusso senza data di pubblicazione non deve spostare il publishedGt del canale API
        assertThat(fdrDominioRepository.findDominioWithMaxDataOraPubblicazione())
            .filteredOn(riga -> COD_DOMINIO.equals(((DominioEntity) riga[0]).getCodDominio()))
            .singleElement()
            .satisfies(riga -> assertThat(riga[1]).isNull());
    }

    @Test
    @DisplayName("Un secondo XML dello stesso flusso diventa la revisione 2 e rende obsoleta la 1")
    void nuovaRevisioneDaXml() throws IOException {
        depositaXml("flusso.xml", flussoXml());
        eseguiStep();

        depositaXml("flusso-corretto.xml", flussoXml()
            .replace("<singoloImportoPagato>70.43<", "<singoloImportoPagato>80.43<")
            .replace("<importoTotalePagamenti>170.43<", "<importoTotalePagamenti>180.43<"));
        eseguiStep();

        List<Fr> flussi = frRepository.findAll().stream()
            .sorted(Comparator.comparing(Fr::getRevisione))
            .toList();
        assertThat(flussi).extracting(Fr::getRevisione).containsExactly(1L, 2L);
        assertThat(flussi.get(0).getObsoleto()).isTrue();
        assertThat(flussi.get(0).getDataOraFlusso())
            .isEqualTo(LocalDateTime.of(2026, 7, 15, 10, 30).minus(1, ChronoUnit.MILLIS));
        assertThat(flussi.get(1).getObsoleto()).isFalse();
        assertThat(flussi.get(1).getDataOraFlusso()).isEqualTo(LocalDateTime.of(2026, 7, 15, 10, 30));
        assertThat(flussi.get(1).getImportoTotalePagamenti()).isEqualTo(180.43);
        assertThat(inputProperties.getProcessedDirPath().resolve("flusso-corretto.xml")).exists();
    }

    @Test
    @DisplayName("Un flusso XML di dominio non censito viene scartato con il motivo")
    void scartaXmlDiDominioNonCensito() throws IOException {
        depositaXml("flusso.xml", flussoXml().replace(COD_DOMINIO, "00000000000"));

        eseguiStep();

        assertThat(frRepository.findAll()).isEmpty();
        assertThat(inputProperties.getErrorDirPath().resolve("flusso.xml.error.txt"))
            .exists()
            .content().contains("non censito");
    }

    @Test
    @DisplayName("Un XML non valido sull'XSD viene scartato con riga e colonna dell'errore")
    void scartaXmlNonValido() throws IOException {
        depositaXml("flusso.xml", flussoXml()
            .replace("<codiceEsitoSingoloPagamento>0<", "<codiceEsitoSingoloPagamento>7<"));

        eseguiStep();

        assertThat(frRepository.findAll()).isEmpty();
        assertThat(inputProperties.getErrorDirPath().resolve("flusso.xml.error.txt"))
            .exists()
            .content().contains("XML non valido rispetto a").contains("riga");
    }
}
