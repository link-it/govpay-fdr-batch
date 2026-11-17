package it.govpay.fdr.batch.filesystem;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import it.govpay.fdr.batch.config.FdrInputProperties;
import it.govpay.fdr.batch.dto.FdrFlowFile;
import it.govpay.fdr.client.model.Payment;
import it.govpay.fdr.client.model.Receiver;
import it.govpay.fdr.client.model.Sender;
import it.govpay.fdr.client.model.SenderTypeEnum;
import it.govpay.fdr.xml.riversamento.CtDatiSingoliPagamenti;
import it.govpay.fdr.xml.riversamento.CtFlussoRiversamento;
import it.govpay.fdr.xml.riversamento.CtIdentificativoUnivoco;
import it.govpay.fdr.xml.riversamento.CtIdentificativoUnivocoPersonaG;
import it.govpay.fdr.xml.riversamento.CtIstitutoMittente;
import it.govpay.fdr.xml.riversamento.CtIstitutoRicevente;
import it.govpay.fdr.xml.riversamento.StTipoIdentificativoUnivoco;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.UnmarshalException;
import jakarta.xml.bind.Unmarshaller;

/**
 * Lettura dei file XML nel tracciato {@code ctFlussoRiversamento}
 * (schema {@code http://www.digitpa.gov.it/schemas/2011/Pagamenti/}).
 * <p>
 * Il flusso viene convertito nello stesso {@link FdrFlowFile} del formato JSON. Il
 * tracciato non porta la revisione, lo stato, le date di pubblicazione e i dati
 * del canale del PSP, che restano quindi non valorizzati: la revisione viene
 * ricavata dalla base dati prima dell'acquisizione.
 * <p>
 * Il file arriva dall'esterno: il parser non risolve DTD ne' entita' esterne.
 */
@Component
@Order(1)
public class XmlFdrFlowFileParser implements FdrFlowFileParser {

    static final String ESTENSIONE = ".xml";
    static final String XSD = "xsd/FlussoRiversamento_1_0_4.xsd";
    static final QName RADICE = new QName("http://www.digitpa.gov.it/schemas/2011/Pagamenti/", "FlussoRiversamento");

    private final ZoneId applicationZoneId;
    private final FdrInputProperties inputProperties;
    private final JAXBContext jaxbContext;
    private final Schema schema;
    private final XMLInputFactory xmlInputFactory;

    public XmlFdrFlowFileParser(ZoneId applicationZoneId, FdrInputProperties inputProperties) {
        this.applicationZoneId = applicationZoneId;
        this.inputProperties = inputProperties;
        try {
            this.jaxbContext = JAXBContext.newInstance(CtFlussoRiversamento.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Impossibile inizializzare JAXB per ctFlussoRiversamento", e);
        }
        this.schema = caricaSchema();
        this.xmlInputFactory = XMLInputFactory.newFactory();
        this.xmlInputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        this.xmlInputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    private static Schema caricaSchema() {
        URL xsd = XmlFdrFlowFileParser.class.getClassLoader().getResource(XSD);
        if (xsd == null) {
            throw new IllegalStateException("XSD " + XSD + " non trovato nel classpath");
        }
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newSchema(xsd);
        } catch (SAXException e) {
            throw new IllegalStateException("XSD " + XSD + " non valido", e);
        }
    }

    @Override
    public boolean supporta(String nomeFile) {
        return nomeFile != null && nomeFile.toLowerCase(Locale.ROOT).endsWith(ESTENSIONE);
    }

    @Override
    public String formato() {
        return "XML";
    }

    @Override
    public FdrFlowFile leggi(Path file) throws IOException {
        return converti(unmarshal(file));
    }

    private CtFlussoRiversamento unmarshal(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(in);
            try {
                Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
                if (inputProperties.getXml().isValidate()) {
                    unmarshaller.setSchema(schema);
                }
                JAXBElement<CtFlussoRiversamento> radice = unmarshaller.unmarshal(reader, CtFlussoRiversamento.class);
                if (!RADICE.equals(radice.getName())) {
                    throw new IllegalArgumentException("Elemento radice " + radice.getName()
                        + " non riconosciuto: atteso " + RADICE);
                }
                return radice.getValue();
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("XML malformato: " + suUnaRiga(e.getMessage()), e);
        } catch (UnmarshalException e) {
            throw new IllegalArgumentException(descrivi(e), e);
        } catch (JAXBException e) {
            throw new IllegalArgumentException("Lettura dell'XML fallita: " + e.getMessage(), e);
        }
    }

    /**
     * Messaggio leggibile per l'operatore: per gli errori di validazione o di sintassi
     * riporta riga e colonna, che l'eccezione JAXB nasconde nella causa.
     */
    private static String descrivi(UnmarshalException e) {
        Throwable causa = e.getLinkedException() != null ? e.getLinkedException() : e.getCause();
        if (causa instanceof SAXParseException sax) {
            return "XML non valido rispetto a " + XSD + " (riga " + sax.getLineNumber()
                + ", colonna " + sax.getColumnNumber() + "): " + sax.getMessage();
        }
        if (causa instanceof XMLStreamException xse) {
            return "XML malformato: " + suUnaRiga(xse.getMessage());
        }
        return "Lettura dell'XML fallita: " + (causa != null ? causa.getMessage() : e.getMessage());
    }

    /** I messaggi StAX vanno a capo prima del dettaglio: nel log e nel .error.txt basta una riga. */
    private static String suUnaRiga(String messaggio) {
        return messaggio != null ? messaggio.replaceAll("\\s*\\R\\s*", " ") : null;
    }

    private FdrFlowFile converti(CtFlussoRiversamento xml) {
        FdrFlowFile flusso = new FdrFlowFile();
        flusso.setFdr(xml.getIdentificativoFlusso());
        flusso.setFdrDate(istante(xml.getDataOraFlusso()));
        flusso.setRegulation(xml.getIdentificativoUnivocoRegolamento());
        flusso.setRegulationDate(data(xml.getDataRegolamento()));
        flusso.setBicCodePouringBank(xml.getCodiceBicBancaDiRiversamento());
        flusso.setSender(mittente(xml.getIstitutoMittente()));
        flusso.setReceiver(ricevente(xml.getIstitutoRicevente()));
        if (xml.getNumeroTotalePagamenti() != null) {
            flusso.setTotPayments(xml.getNumeroTotalePagamenti().longValue());
        }
        flusso.setSumPayments(importo(xml.getImportoTotalePagamenti()));

        List<Payment> pagamenti = new ArrayList<>();
        long indice = 1;
        for (CtDatiSingoliPagamenti dsp : xml.getDatiSingoliPagamenti()) {
            pagamenti.add(pagamento(dsp, indice++));
        }
        flusso.setPayments(pagamenti);
        return flusso;
    }

    private static Sender mittente(CtIstitutoMittente mittente) {
        if (mittente == null) {
            return null;
        }
        Sender sender = new Sender();
        CtIdentificativoUnivoco id = mittente.getIdentificativoUnivocoMittente();
        if (id != null) {
            sender.setId(id.getCodiceIdentificativoUnivoco());
            sender.setPspId(id.getCodiceIdentificativoUnivoco());
            sender.setType(tipoMittente(id.getTipoIdentificativoUnivoco()));
        }
        sender.setPspName(mittente.getDenominazioneMittente());
        return sender;
    }

    private static SenderTypeEnum tipoMittente(StTipoIdentificativoUnivoco tipo) {
        if (tipo == null) {
            return null;
        }
        return switch (tipo) {
            case B -> SenderTypeEnum.BIC_CODE;
            case A -> SenderTypeEnum.ABI_CODE;
            case G -> SenderTypeEnum.LEGAL_PERSON;
        };
    }

    private static Receiver ricevente(CtIstitutoRicevente ricevente) {
        if (ricevente == null) {
            return null;
        }
        Receiver receiver = new Receiver();
        CtIdentificativoUnivocoPersonaG id = ricevente.getIdentificativoUnivocoRicevente();
        if (id != null) {
            receiver.setId(id.getCodiceIdentificativoUnivoco());
            receiver.setOrganizationId(id.getCodiceIdentificativoUnivoco());
        }
        receiver.setOrganizationName(ricevente.getDenominazioneRicevente());
        return receiver;
    }

    private Payment pagamento(CtDatiSingoliPagamenti dsp, long indice) {
        Payment payment = new Payment();
        payment.setIndex(indice);
        payment.setIuv(dsp.getIdentificativoUnivocoVersamento());
        payment.setIur(dsp.getIdentificativoUnivocoRiscossione());
        // indiceDatiSingoloPagamento e' facoltativo: in sua assenza il pagamento si
        // riferisce al primo trasferimento, come nella riconciliazione di GovPay
        Integer indiceDati = dsp.getIndiceDatiSingoloPagamento();
        payment.setIdTransfer(indiceDati != null ? indiceDati.longValue() : 1L);
        payment.setPay(importo(dsp.getSingoloImportoPagato()));
        payment.setPayStatus(esito(dsp.getCodiceEsitoSingoloPagamento()));
        // La data di esito e' una data di calendario: la mezzanotte nel fuso applicativo
        // fa si' che la conversione dello step 4 restituisca lo stesso giorno
        LocalDate dataEsito = data(dsp.getDataEsitoSingoloPagamento());
        if (dataEsito != null) {
            payment.setPayDate(dataEsito.atStartOfDay(applicationZoneId).toOffsetDateTime());
        }
        return payment;
    }

    private static Payment.PayStatusEnum esito(String codice) {
        if (codice == null) {
            return null;
        }
        return switch (codice.trim()) {
            case "0" -> Payment.PayStatusEnum.EXECUTED;
            case "3" -> Payment.PayStatusEnum.REVOKED;
            case "4" -> Payment.PayStatusEnum.STAND_IN;
            case "8" -> Payment.PayStatusEnum.STAND_IN_NO_RPT;
            case "9" -> Payment.PayStatusEnum.NO_RPT;
            default -> throw new IllegalArgumentException(
                "codiceEsitoSingoloPagamento non riconosciuto: " + codice);
        };
    }

    /**
     * Data/ora del flusso. Se l'XML non indica il fuso, l'orario e' inteso nel fuso
     * applicativo con il suo offset reale (CET o CEST), non con un offset fisso.
     */
    private OffsetDateTime istante(XMLGregorianCalendar cal) {
        if (cal == null) {
            return null;
        }
        if (cal.getTimezone() != DatatypeConstants.FIELD_UNDEFINED) {
            return cal.toGregorianCalendar().toZonedDateTime().toOffsetDateTime();
        }
        int secondi = cal.getSecond() != DatatypeConstants.FIELD_UNDEFINED ? cal.getSecond() : 0;
        int millis = cal.getMillisecond() != DatatypeConstants.FIELD_UNDEFINED ? cal.getMillisecond() : 0;
        LocalDateTime locale = LocalDateTime.of(cal.getYear(), cal.getMonth(), cal.getDay(),
            cal.getHour(), cal.getMinute(), secondi, millis * 1_000_000);
        return locale.atZone(applicationZoneId).toOffsetDateTime();
    }

    private static LocalDate data(XMLGregorianCalendar cal) {
        return cal != null ? LocalDate.of(cal.getYear(), cal.getMonth(), cal.getDay()) : null;
    }

    private static Double importo(BigDecimal valore) {
        return valore != null ? valore.doubleValue() : null;
    }
}
