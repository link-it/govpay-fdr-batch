package it.govpay.fdr.batch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
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
    @DisplayName("Un flusso di dominio non censito viene scartato senza toccare il database")
    void scartaIlDominioNonCensito() throws IOException {
        deposita("flusso.json", FLUSSO_JSON.replace(COD_DOMINIO, "00000000000"));

        eseguiStep();

        assertThat(frRepository.findAll()).isEmpty();
        assertThat(inputProperties.getErrorDirPath().resolve("flusso.json.error.txt"))
            .exists()
            .content().contains("non censito");
    }
}
