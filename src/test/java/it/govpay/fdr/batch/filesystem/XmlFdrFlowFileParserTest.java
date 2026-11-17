package it.govpay.fdr.batch.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.govpay.fdr.batch.config.FdrInputProperties;
import it.govpay.fdr.batch.dto.FdrFlowFile;
import it.govpay.fdr.client.model.Payment;
import it.govpay.fdr.client.model.SenderTypeEnum;

@DisplayName("XmlFdrFlowFileParser Tests")
class XmlFdrFlowFileParserTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Rome");

    @TempDir
    Path tempDir;

    private FdrInputProperties properties;
    private XmlFdrFlowFileParser parser;

    @BeforeEach
    void setUp() {
        properties = new FdrInputProperties();
        parser = new XmlFdrFlowFileParser(ZONE_ID, properties);
    }

    private static String risorsa(String nome) throws IOException {
        try (InputStream in = XmlFdrFlowFileParserTest.class.getResourceAsStream("/fdr-xml/" + nome)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path scrivi(String contenuto) throws IOException {
        Path file = tempDir.resolve("flusso.xml.node.processing");
        Files.writeString(file, contenuto);
        return file;
    }

    private Path copia(String nome) throws IOException {
        Path file = tempDir.resolve(nome + ".node.processing");
        try (InputStream in = XmlFdrFlowFileParserTest.class.getResourceAsStream("/fdr-xml/" + nome)) {
            Files.copy(in, file);
        }
        return file;
    }

    @Test
    @DisplayName("Gestisce solo i file .xml, senza distinguere maiuscole e minuscole")
    void supporta() {
        assertThat(parser.supporta("flusso.xml")).isTrue();
        assertThat(parser.supporta("FLUSSO.XML")).isTrue();
        assertThat(parser.supporta("flusso.json")).isFalse();
        assertThat(parser.supporta("flusso.xml.bak")).isFalse();
    }

    @Test
    @DisplayName("Flusso con namespace di default: testata, mittente e ricevente")
    void testata() throws IOException {
        FdrFlowFile flusso = parser.leggi(copia("flusso-valido.xml"));

        assertThat(flusso.getFdr()).isEqualTo("2026-07-15ABI03365-B6FXC00000004719");
        assertThat(flusso.getRevision()).isNull();
        assertThat(flusso.getStatus()).isNull();
        assertThat(flusso.getPublished()).isNull();
        assertThat(flusso.getRegulation()).isEqualTo("Bonifico SEPA-03365-B6FXC");
        assertThat(flusso.getRegulationDate()).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(flusso.getBicCodePouringBank()).isEqualTo("CHBKITMM");
        assertThat(flusso.getTotPayments()).isEqualTo(3L);
        assertThat(flusso.getSumPayments()).isEqualTo(170.43);

        assertThat(flusso.getSender().getPspId()).isEqualTo("ABI03365");
        assertThat(flusso.getSender().getId()).isEqualTo("ABI03365");
        assertThat(flusso.getSender().getType()).isEqualTo(SenderTypeEnum.BIC_CODE);
        assertThat(flusso.getSender().getPspName()).isEqualTo("CHERRY BANK SPA");
        assertThat(flusso.getSender().getPspBrokerId()).isNull();
        assertThat(flusso.getSender().getChannelId()).isNull();

        assertThat(flusso.getReceiver().getOrganizationId()).isEqualTo("09788660968");
        assertThat(flusso.getReceiver().getId()).isEqualTo("09788660968");
        assertThat(flusso.getReceiver().getOrganizationName()).isEqualTo("RIENERGIA SRLS");
    }

    @Test
    @DisplayName("Data/ora senza offset in ora legale: interpretata come CEST (+02:00)")
    void dataOraLegale() throws IOException {
        FdrFlowFile flusso = parser.leggi(copia("flusso-valido.xml"));

        assertThat(flusso.getFdrDate())
            .isEqualTo(OffsetDateTime.of(2026, 7, 15, 10, 30, 0, 0, ZoneOffset.ofHours(2)));
    }

    @Test
    @DisplayName("Data/ora senza offset in ora solare: interpretata come CET (+01:00)")
    void dataOraSolare() throws IOException {
        FdrFlowFile flusso = parser.leggi(copia("flusso-prefisso-bom.xml"));

        assertThat(flusso.getFdrDate())
            .isEqualTo(OffsetDateTime.of(2026, 1, 15, 10, 30, 0, 0, ZoneOffset.ofHours(1)));
    }

    @Test
    @DisplayName("Data/ora con offset esplicito: offset rispettato")
    void dataOraConOffset() throws IOException {
        String xml = risorsa("flusso-valido.xml")
            .replace("2026-07-15T10:30:00<", "2026-07-15T08:30:00Z<");

        FdrFlowFile flusso = parser.leggi(scrivi(xml));

        assertThat(flusso.getFdrDate().toInstant())
            .isEqualTo(OffsetDateTime.of(2026, 7, 15, 8, 30, 0, 0, ZoneOffset.UTC).toInstant());
    }

    @Test
    @DisplayName("Pagamenti: esiti 0/4/8, indice facoltativo, data esito a mezzanotte locale")
    void pagamenti() throws IOException {
        FdrFlowFile flusso = parser.leggi(copia("flusso-valido.xml"));

        assertThat(flusso.getPayments()).hasSize(3);

        Payment primo = flusso.getPayments().get(0);
        assertThat(primo.getIndex()).isEqualTo(1L);
        assertThat(primo.getIuv()).isEqualTo("00551000024916517");
        assertThat(primo.getIur()).isEqualTo("8c1d4c6cf55a42108cc8820456031b0f");
        assertThat(primo.getIdTransfer()).isEqualTo(2L);
        assertThat(primo.getPay()).isEqualTo(70.43);
        assertThat(primo.getPayStatus()).isEqualTo(Payment.PayStatusEnum.EXECUTED);
        assertThat(primo.getPayDate()).isEqualTo(LocalDate.of(2026, 7, 14).atStartOfDay(ZONE_ID).toOffsetDateTime());

        Payment secondo = flusso.getPayments().get(1);
        assertThat(secondo.getIndex()).isEqualTo(2L);
        assertThat(secondo.getIdTransfer()).isEqualTo(1L);
        assertThat(secondo.getPayStatus()).isEqualTo(Payment.PayStatusEnum.STAND_IN);

        assertThat(flusso.getPayments().get(2).getPayStatus()).isEqualTo(Payment.PayStatusEnum.STAND_IN_NO_RPT);
    }

    @Test
    @DisplayName("Prefisso pay_i, BOM UTF-8, mittente ABI ed esito 9")
    void prefissoEBom() throws IOException {
        FdrFlowFile flusso = parser.leggi(copia("flusso-prefisso-bom.xml"));

        assertThat(flusso.getFdr()).isEqualTo("2026-01-15ABI03365-B6FXC00000000001");
        assertThat(flusso.getSender().getType()).isEqualTo(SenderTypeEnum.ABI_CODE);
        assertThat(flusso.getSender().getPspName()).isNull();
        assertThat(flusso.getReceiver().getOrganizationName()).isNull();
        assertThat(flusso.getBicCodePouringBank()).isNull();
        assertThat(flusso.getPayments()).singleElement()
            .extracting(Payment::getPayStatus).isEqualTo(Payment.PayStatusEnum.NO_RPT);
    }

    @Test
    @DisplayName("Prefisso ns2")
    void prefissoNs2() throws IOException {
        String xml = risorsa("flusso-prefisso-bom.xml").replace("pay_i:", "ns2:").replace("xmlns:pay_i", "xmlns:ns2");

        FdrFlowFile flusso = parser.leggi(scrivi(xml));

        assertThat(flusso.getFdr()).isEqualTo("2026-01-15ABI03365-B6FXC00000000001");
        assertThat(flusso.getPayments()).hasSize(1);
    }

    @Test
    @DisplayName("XML non valido sull'XSD: errore con riga e colonna")
    void nonValidoSuXsd() throws IOException {
        String xml = risorsa("flusso-valido.xml")
            .replace("<codiceEsitoSingoloPagamento>0<", "<codiceEsitoSingoloPagamento>7<");
        Path file = scrivi(xml);

        assertThatThrownBy(() -> parser.leggi(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("XML non valido rispetto a")
            .hasMessageContaining("riga");
    }

    @Test
    @DisplayName("Pagamento revocato con importo negativo: scartato dall'XSD, acquisibile senza validazione")
    void revocaNegativa() throws IOException {
        String xml = risorsa("flusso-valido.xml")
            .replace("<singoloImportoPagato>70.43<", "<singoloImportoPagato>-70.43<")
            .replace("<codiceEsitoSingoloPagamento>0<", "<codiceEsitoSingoloPagamento>3<");
        Path file = scrivi(xml);

        assertThatThrownBy(() -> parser.leggi(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("XML non valido rispetto a");

        properties.getXml().setValidate(false);
        Payment revocato = parser.leggi(file).getPayments().get(0);
        assertThat(revocato.getPay()).isEqualTo(-70.43);
        assertThat(revocato.getPayStatus()).isEqualTo(Payment.PayStatusEnum.REVOKED);
    }

    @Test
    @DisplayName("Senza validazione un esito sconosciuto viene comunque rifiutato")
    void esitoSconosciutoSenzaValidazione() throws IOException {
        properties.getXml().setValidate(false);
        String xml = risorsa("flusso-valido.xml")
            .replace("<codiceEsitoSingoloPagamento>0<", "<codiceEsitoSingoloPagamento>7<");
        Path file = scrivi(xml);

        assertThatThrownBy(() -> parser.leggi(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("codiceEsitoSingoloPagamento non riconosciuto: 7");
    }

    @Test
    @DisplayName("XML malformato o troncato")
    void malformato() throws IOException {
        String xml = risorsa("flusso-valido.xml");
        Path file = scrivi(xml.substring(0, xml.length() / 2));

        assertThatThrownBy(() -> parser.leggi(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("XML");
    }

    @Test
    @DisplayName("Radice diversa da FlussoRiversamento: rifiutata anche senza validazione")
    void radiceErrata() throws IOException {
        properties.getXml().setValidate(false);
        String xml = risorsa("flusso-valido.xml").replace("FlussoRiversamento", "RT");
        Path file = scrivi(xml);

        assertThatThrownBy(() -> parser.leggi(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Elemento radice")
            .hasMessageContaining("RT");
    }

    @Test
    @DisplayName("DOCTYPE con entita' esterna: il file locale non viene letto")
    void entitaEsterna() throws IOException {
        Path segreto = tempDir.resolve("segreto.txt");
        Files.writeString(segreto, "CONTENUTO-SEGRETO");
        String xml = risorsa("flusso-valido.xml")
            .replace("<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE FlussoRiversamento ["
                    + "<!ENTITY xxe SYSTEM \"" + segreto.toUri() + "\">]>")
            .replace("CHERRY BANK SPA", "&xxe;");

        for (boolean valida : new boolean[] { true, false }) {
            properties.getXml().setValidate(valida);
            Path file = scrivi(xml);
            assertThatThrownBy(() -> parser.leggi(file))
                .isInstanceOf(IllegalArgumentException.class)
                .message().doesNotContain("CONTENUTO-SEGRETO");
        }
    }
}
