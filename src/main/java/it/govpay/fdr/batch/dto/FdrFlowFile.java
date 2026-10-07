package it.govpay.fdr.batch.dto;

import java.io.Serial;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import it.govpay.fdr.client.model.Payment;
import it.govpay.fdr.client.model.SingleFlowResponse;

/**
 * Formato del file JSON accettato dall'acquisizione da file system.
 * <p>
 * Estende {@link SingleFlowResponse} (la response della GET puntuale sul flusso
 * dell'API FDR di pagoPA) aggiungendo la lista dei pagamenti inline: il file e'
 * quindi l'unione di cio' che le API espongono su due endpoint distinti
 * ({@code .../psps/{pspId}} e {@code .../psps/{pspId}/payments}).
 * <p>
 * I campi non riconosciuti vengono ignorati: pagoPA puo' aggiungere attributi
 * alla response senza che questo invalidi i file gia' depositati.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FdrFlowFile extends SingleFlowResponse {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String JSON_PROPERTY_PAYMENTS = "payments";

    @JsonProperty(JSON_PROPERTY_PAYMENTS)
    private List<Payment> payments;

    public List<Payment> getPayments() {
        return payments;
    }

    public void setPayments(List<Payment> payments) {
        this.payments = payments;
    }

    /**
     * Numero di pagamenti dichiarato nella testata del flusso.
     * <p>
     * pagoPA espone sia {@code totPayments} (dichiarato dal PSP) sia
     * {@code computedTotPayments} (ricalcolato dal Nodo) e nei file scaricati puo'
     * essere presente solo il secondo: si usa il primo se valorizzato, altrimenti
     * il secondo, per non perdere i controlli di quadratura.
     */
    public Long resolveNumeroPagamenti() {
        return getTotPayments() != null ? getTotPayments() : getComputedTotPayments();
    }

    /**
     * Importo totale dichiarato nella testata del flusso, con lo stesso fallback
     * descritto in {@link #resolveNumeroPagamenti()}.
     */
    public Double resolveImportoTotalePagamenti() {
        return getSumPayments() != null ? getSumPayments() : getComputedSumPayments();
    }
}
