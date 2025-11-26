package it.govpay.fdr.batch.gde.mapper;

import it.govpay.fdr.batch.entity.Fr;
import it.govpay.gde.client.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Mapper for creating GDE events from FDR batch operations.
 * <p>
 * This mapper creates NuovoEvento objects to track FDR acquisition operations
 * including successful and failed API calls to pagoPA.
 */
@Slf4j
@Component
public class EventoFdrMapper {

    @Value("${govpay.batch.cluster-id}")
    private String clusterId;

    /**
     * Creates a base event with common fields from Fr entity.
     *
     * @param fr                Fr entity containing FDR data
     * @param tipoEvento       Event type (e.g., GET_PUBLISHED_FLOWS, GET_FLOW_DETAILS)
     * @param transactionId    Unique transaction identifier
     * @param dataStart        Event start timestamp
     * @param dataEnd          Event end timestamp
     * @return NuovoEvento with base fields populated
     */
    public NuovoEvento createEvento(Fr fr, String tipoEvento, String transactionId,
                                     OffsetDateTime dataStart, OffsetDateTime dataEnd) {
        NuovoEvento nuovoEvento = new NuovoEvento();

        // Set FDR-specific identifiers
        if (fr != null) {
            nuovoEvento.setIdDominio(fr.getCodDominio());
            nuovoEvento.setDatiPagoPA(createDatiPagoPA(fr));
        }

        // Set event metadata
        nuovoEvento.setCategoriaEvento(CategoriaEvento.INTERFACCIA);
        nuovoEvento.setClusterId(clusterId);
        nuovoEvento.setDataEvento(dataStart);
        nuovoEvento.setDurataEvento(dataEnd.toEpochSecond() - dataStart.toEpochSecond());
        nuovoEvento.setRuolo(RuoloEvento.CLIENT);
        nuovoEvento.setComponente(ComponenteEvento.API_PAGOPA);
        nuovoEvento.setTipoEvento(tipoEvento);
        nuovoEvento.setTransactionId(transactionId);

        return nuovoEvento;
    }

    /**
     * Creates an OK event for successful operations.
     *
     * @param fr                Fr entity
     * @param tipoEvento       Event type
     * @param transactionId    Transaction ID
     * @param dataStart        Start timestamp
     * @param dataEnd          End timestamp
     * @return NuovoEvento with OK outcome
     */
    public NuovoEvento createEventoOk(Fr fr, String tipoEvento, String transactionId,
                                       OffsetDateTime dataStart, OffsetDateTime dataEnd) {
        NuovoEvento nuovoEvento = createEvento(fr, tipoEvento, transactionId, dataStart, dataEnd);
        nuovoEvento.setEsito(EsitoEvento.OK);
        return nuovoEvento;
    }

    /**
     * Creates a KO/FAIL event for failed operations.
     *
     * @param fr                Fr entity
     * @param tipoEvento       Event type
     * @param transactionId    Transaction ID
     * @param dataStart        Start timestamp
     * @param dataEnd          End timestamp
     * @param responseEntity   Response entity (if available)
     * @param exception        Exception (if any)
     * @return NuovoEvento with KO/FAIL outcome
     */
    public NuovoEvento createEventoKo(Fr fr, String tipoEvento, String transactionId,
                                       OffsetDateTime dataStart, OffsetDateTime dataEnd,
                                       ResponseEntity<?> responseEntity, RestClientException exception) {
        NuovoEvento nuovoEvento = createEvento(fr, tipoEvento, transactionId, dataStart, dataEnd);
        extractExceptionInfo(responseEntity, exception, nuovoEvento);
        return nuovoEvento;
    }

    /**
     * Sets request details on the event.
     *
     * @param nuovoEvento      Event to update
     * @param urlOperazione    Operation URL
     * @param httpMethod       HTTP method (GET, POST, etc.)
     * @param headers          HTTP headers
     */
    public void setParametriRichiesta(NuovoEvento nuovoEvento, String urlOperazione,
                                       String httpMethod, List<Header> headers) {
        DettaglioRichiesta dettaglioRichiesta = new DettaglioRichiesta();
        dettaglioRichiesta.setDataOraRichiesta(nuovoEvento.getDataEvento());
        dettaglioRichiesta.setMethod(httpMethod);
        dettaglioRichiesta.setUrl(urlOperazione);
        dettaglioRichiesta.setHeaders(headers);

        nuovoEvento.setParametriRichiesta(dettaglioRichiesta);
    }

    /**
     * Sets response details on the event.
     *
     * @param nuovoEvento      Event to update
     * @param dataEnd          Response timestamp
     * @param responseEntity   Response entity
     * @param exception        Exception (if any)
     */
    public void setParametriRisposta(NuovoEvento nuovoEvento, OffsetDateTime dataEnd,
                                      ResponseEntity<?> responseEntity, RestClientException exception) {
        DettaglioRisposta dettaglioRisposta = new DettaglioRisposta();
        dettaglioRisposta.setDataOraRisposta(dataEnd);

        List<Header> headers = new ArrayList<>();

        if (responseEntity != null) {
            dettaglioRisposta.setStatus(BigDecimal.valueOf(responseEntity.getStatusCode().value()));

            HttpHeaders httpHeaders = responseEntity.getHeaders();
            httpHeaders.forEach((key, value) -> {
                if (!value.isEmpty()) {
                    Header header = new Header();
                    header.setNome(key);
                    header.setValore(value.get(0));
                    headers.add(header);
                }
            });
        } else if (exception instanceof HttpStatusCodeException httpStatusCodeException) {
            dettaglioRisposta.setStatus(BigDecimal.valueOf(httpStatusCodeException.getStatusCode().value()));

            HttpHeaders httpHeaders = httpStatusCodeException.getResponseHeaders();
            if (httpHeaders != null) {
                httpHeaders.forEach((key, value) -> {
                    if (!value.isEmpty()) {
                        Header header = new Header();
                        header.setNome(key);
                        header.setValore(value.get(0));
                        headers.add(header);
                    }
                });
            }
        } else {
            dettaglioRisposta.setStatus(BigDecimal.valueOf(500));
        }

        dettaglioRisposta.setHeaders(headers);
        nuovoEvento.setParametriRisposta(dettaglioRisposta);
    }

    /**
     * Creates DatiPagoPA object from Fr entity.
     *
     * @param fr Fr entity
     * @return DatiPagoPA with FDR-specific data
     */
    private DatiPagoPA createDatiPagoPA(Fr fr) {
        DatiPagoPA datiPagoPA = new DatiPagoPA();
        datiPagoPA.setIdPsp(fr.getCodPsp());
        datiPagoPA.setIdDominio(fr.getCodDominio());
        datiPagoPA.setIdFlusso(fr.getCodFlusso());
        return datiPagoPA;
    }

    /**
     * Extracts error information from exception and sets outcome (KO or FAIL).
     *
     * @param responseEntity Response entity
     * @param exception      Exception
     * @param nuovoEvento    Event to update
     */
    private void extractExceptionInfo(ResponseEntity<?> responseEntity, RestClientException exception,
                                       NuovoEvento nuovoEvento) {
        if (exception != null) {
            if (exception instanceof HttpStatusCodeException httpStatusCodeException) {
                nuovoEvento.setDettaglioEsito(httpStatusCodeException.getResponseBodyAsString());
                nuovoEvento.setSottotipoEsito(httpStatusCodeException.getStatusCode().value() + "");

                if (httpStatusCodeException.getStatusCode().is5xxServerError()) {
                    nuovoEvento.setEsito(EsitoEvento.FAIL);
                } else {
                    nuovoEvento.setEsito(EsitoEvento.KO);
                }
            } else {
                nuovoEvento.setDettaglioEsito(exception.getMessage());
                nuovoEvento.setSottotipoEsito("500");
                nuovoEvento.setEsito(EsitoEvento.FAIL);
            }
        } else if (responseEntity != null) {
            nuovoEvento.setDettaglioEsito(HttpStatus.valueOf(responseEntity.getStatusCode().value()).getReasonPhrase());
            nuovoEvento.setSottotipoEsito("" + responseEntity.getStatusCode().value());

            if (responseEntity.getStatusCode().is5xxServerError()) {
                nuovoEvento.setEsito(EsitoEvento.FAIL);
            } else {
                nuovoEvento.setEsito(EsitoEvento.KO);
            }
        }
    }
}
