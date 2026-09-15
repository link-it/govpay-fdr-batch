package it.govpay.fdr.batch.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.fdr.batch.config.FdrApiClientConfig;
import it.govpay.fdr.batch.dto.FdrClaimedFile;
import it.govpay.fdr.batch.dto.FdrFileItem;
import it.govpay.fdr.batch.dto.FdrFlowFile;
import it.govpay.fdr.batch.repository.FrRepository;
import it.govpay.fdr.batch.step4.FdrPaymentsProcessor;
import it.govpay.fdr.batch.step4.FdrPaymentsProcessor.FdrCompleteData;
import it.govpay.fdr.batch.step4.FdrPaymentsProcessor.PaymentData;
import it.govpay.fdr.client.model.Payment;
import it.govpay.fdr.client.model.ReportingFlowStatusEnum;
import lombok.extern.slf4j.Slf4j;

/**
 * Analizza un file depositato nella directory di acquisizione e lo trasforma nello
 * stesso {@link FdrCompleteData} prodotto dallo step 4, cosi' che la persistenza su
 * FR/RENDICONTAZIONI (riconciliazione con i pagamenti, controlli di quadratura,
 * gestione delle revisioni) sia esattamente quella del canale API.
 * <p>
 * Non solleva eccezioni per i file non validi: le scarta valorizzando l'esito
 * dell'item, in modo che un singolo file malformato non interrompa il job.
 */
@Component
@Slf4j
public class FdrFileSystemProcessor implements ItemProcessor<FdrClaimedFile, FdrFileItem> {

    private final DominioRepository dominioRepository;
    private final FrRepository frRepository;
    private final FdrPaymentsProcessor paymentsProcessor;
    private final ZoneId applicationZoneId;
    private final JsonMapper jsonMapper;

    public FdrFileSystemProcessor(DominioRepository dominioRepository,
                                  FrRepository frRepository,
                                  FdrPaymentsProcessor paymentsProcessor,
                                  ZoneId applicationZoneId,
                                  FdrApiClientConfig fdrApiClientConfig) {
        this.dominioRepository = dominioRepository;
        this.frRepository = frRepository;
        this.paymentsProcessor = paymentsProcessor;
        this.applicationZoneId = applicationZoneId;
        // Stesso mapper usato verso le API pagoPA: i file hanno lo stesso formato date
        // (epoch con nanosecondi, millisecondi a lunghezza variabile, date senza offset)
        this.jsonMapper = fdrApiClientConfig.createPagoPAObjectMapper();
    }

    @Override
    public FdrFileItem process(FdrClaimedFile claimed) {
        Path file = claimed.file();
        String nome = claimed.nomeOriginale();
        log.info("Analisi del flusso da file system: {}", nome);

        FdrFlowFile flusso;
        try {
            flusso = leggi(file);
        } catch (IOException e) {
            return scarta(claimed, null, null, "Lettura del file fallita: " + e.getMessage());
        } catch (RuntimeException e) {
            return scarta(claimed, null, null, "Parsing del file fallito: " + e.getMessage());
        }

        String codDominio = flusso.getReceiver() != null ? flusso.getReceiver().getOrganizationId() : null;
        String codFlusso = flusso.getFdr();
        String codPsp = flusso.getSender() != null ? flusso.getSender().getPspId() : null;

        String erroreValidazione = valida(flusso, codDominio, codFlusso, codPsp);
        if (erroreValidazione != null) {
            return scarta(claimed, codDominio, codFlusso, erroreValidazione);
        }

        Optional<DominioEntity> dominioOpt = dominioRepository.findByCodDominio(codDominio);
        if (dominioOpt.isEmpty()) {
            return scarta(claimed, codDominio, codFlusso,
                MessageFormat.format("Dominio {0} non censito", codDominio));
        }
        if (!Boolean.TRUE.equals(dominioOpt.get().getAbilitato())) {
            return scarta(claimed, codDominio, codFlusso,
                MessageFormat.format("Dominio {0} non abilitato", codDominio));
        }

        if (frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(
                codDominio, codFlusso, codPsp, flusso.getRevision())) {
            String motivo = MessageFormat.format(
                "Flusso [Dominio:{0} Flusso:{1} PSP:{2} Revisione:{3}] gia'' presente in FR",
                codDominio, codFlusso, codPsp, flusso.getRevision());
            log.info("{}: il file {} viene archiviato senza reinserimento", motivo, nome);
            return FdrFileItem.duplicato(file, nome, codDominio, codFlusso, motivo);
        }

        if (flusso.getStatus() != null && flusso.getStatus() != ReportingFlowStatusEnum.PUBLISHED) {
            log.warn("Il flusso {} del file {} ha stato {}: viene comunque acquisito",
                codFlusso, nome, flusso.getStatus());
        }

        FdrCompleteData data = converti(flusso, codDominio, codPsp);
        log.info("Flusso {} del dominio {} pronto per l'acquisizione: {} pagamenti nel file, {} dichiarati in testata",
            codFlusso, codDominio, data.getPayments().size(), data.getNumeroPagamenti());
        return FdrFileItem.acquisire(file, nome, data);
    }

    private FdrFlowFile leggi(Path file) throws IOException {
        return jsonMapper.readValue(Files.readString(file), FdrFlowFile.class);
    }

    /**
     * Verifica i soli dati senza i quali il flusso non e' identificabile o non e'
     * acquisibile. I controlli di merito (quadratura importi e numero pagamenti)
     * restano in carico alla persistenza, che li registra come anomalie sull'FR.
     *
     * @return la descrizione dello scarto, oppure {@code null} se il flusso e' valido
     */
    private String valida(FdrFlowFile flusso, String codDominio, String codFlusso, String codPsp) {
        if (isBlank(codFlusso)) {
            return "Identificativo del flusso (fdr) assente";
        }
        if (flusso.getRevision() == null) {
            return "Revisione del flusso (revision) assente";
        }
        if (isBlank(codDominio)) {
            return "Identificativo del dominio (receiver.organizationId) assente";
        }
        if (isBlank(codPsp)) {
            return "Identificativo del PSP (sender.pspId) assente";
        }
        if (flusso.getPayments() == null) {
            return "Lista dei pagamenti (payments) assente: il file non e' nel formato atteso";
        }
        return null;
    }

    private FdrCompleteData converti(FdrFlowFile flusso, String codDominio, String codPsp) {
        List<PaymentData> pagamenti = flusso.getPayments().stream()
            .map(this::convertiPagamento)
            .toList();

        return FdrCompleteData.builder()
            // Nessuna riga FR_TEMP associata: il flusso non proviene dalla pipeline API
            .frTempId(null)
            .codPsp(codPsp)
            .codDominio(codDominio)
            .codFlusso(flusso.getFdr())
            .iur(flusso.getRegulation())
            .dataOraFlusso(toLocalDateTime(flusso.getFdrDate()))
            .dataRegolamento(toLocalDateTime(flusso.getRegulationDate()))
            .numeroPagamenti(flusso.resolveNumeroPagamenti())
            .importoTotalePagamenti(flusso.resolveImportoTotalePagamenti())
            .codBicRiversamento(flusso.getBicCodePouringBank())
            .codPspMittente(codPsp)
            .ragioneSocialePsp(flusso.getSender() != null ? flusso.getSender().getPspName() : null)
            .ragioneSocialeDominio(flusso.getReceiver() != null ? flusso.getReceiver().getOrganizationName() : null)
            .codIntermediarioPsp(flusso.getSender() != null ? flusso.getSender().getPspBrokerId() : null)
            .codCanale(flusso.getSender() != null ? flusso.getSender().getChannelId() : null)
            .dataOraPubblicazione(toLocalDateTime(flusso.getPublished()))
            .dataOraAggiornamento(toLocalDateTime(flusso.getUpdated()))
            .revisione(flusso.getRevision())
            .stato(flusso.getStatus() != null ? flusso.getStatus().name() : null)
            .payments(pagamenti)
            .build();
    }

    private PaymentData convertiPagamento(Payment payment) {
        // Riuso della conversione dello step 4: stessa codifica di payStatus e stesso
        // fuso orario, cosi' un flusso da file e uno da API producono gli stessi dati
        return paymentsProcessor.convertPayment(payment);
    }

    private FdrFileItem scarta(FdrClaimedFile claimed, String codDominio, String codFlusso, String motivo) {
        log.error("Flusso da file system scartato [file:{} Dominio:{} Flusso:{}]: {}",
            claimed.nomeOriginale(), codDominio, codFlusso, motivo);
        return FdrFileItem.scartato(claimed.file(), claimed.nomeOriginale(), codDominio, codFlusso, motivo);
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime offsetDateTime) {
        if (offsetDateTime == null) {
            return null;
        }
        return offsetDateTime.atZoneSameInstant(applicationZoneId).toLocalDateTime()
            .truncatedTo(ChronoUnit.MILLIS);
    }

    private LocalDateTime toLocalDateTime(LocalDate localDate) {
        return localDate != null ? localDate.atStartOfDay() : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
