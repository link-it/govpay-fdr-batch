# Release Notes

## 1.0.7 — 2026-07-14

Release di manutenzione: gestione della finestra temporale accettata da pagoPA per il parametro `publishedGt` nel recupero dei flussi pubblicati.

### Correzioni — API pagoPA (`publishedGt`)
L'API pagoPA `getAllPublishedFlows` restituisce **HTTP 400** (`FDR-1000`, *"The date cannot be older than 30 days"*) quando il parametro `publishedGt` è più vecchio di 30 giorni. Il batch calcola `publishedGt` come massima data di pubblicazione dei flussi già acquisiti per il dominio: per i domini con ultima acquisizione oltre tale finestra la chiamata falliva.

È stata introdotta una gestione **configurabile** in `FdrApiService.getAllPublishedFlows`: quando la `publishedGt` calcolata supera la finestra consentita, viene applicata la strategia impostata.

### Configurazione
Nuove proprietà (prefix `pagopa.fdr`):

| Proprietà | Default | Descrizione |
|---|---|---|
| `pagopa.fdr.published-gt-max-age-days` | `30` | Soglia in giorni oltre la quale `publishedGt` è considerata fuori finestra. |
| `pagopa.fdr.published-gt-stale-strategy` | `ALL` | Strategia quando la data è fuori finestra: `ALL` o `CLAMP`. |

Strategie:
- **`ALL`** (default): non invia `publishedGt` (`null`) e recupera **tutti** i flussi pubblicati. Nessun flusso viene perso; il controllo di esistenza in `FdrHeadersWriter` (per `codDominio + codFlusso + psp + revisione`) evita ri-acquisizioni dei flussi già presenti in `FR`/`FR_TEMP`. Costo: la sola chiamata di elenco è più pesante finché il dominio resta "indietro".
- **`CLAMP`**: riporta `publishedGt` a `adesso - published-gt-max-age-days`, limitando l'elenco agli ultimi giorni consentiti. Elenco più corto, ma può non recuperare flussi pubblicati oltre la finestra e non ancora acquisiti.

### Test
- Aggiunti test in `FdrApiServiceGdeIntegrationTest` per le tre casistiche: fuori finestra con `ALL` (invia `null`), fuori finestra con `CLAMP` (riporta la data a ~adesso-30g), dentro finestra (data invariata).
- Corretto un bug latente *DST-dependent* in `FdrMetadataProcessorTest.testOffsetDateTimeConversion` (l'offset veniva calcolato su `LocalDateTime.now()` anziché sulla data del test, con fallimento di 1 ora in periodo di ora legale).

### Compatibilità
Nessuna breaking change: aggiornamento drop-in rispetto alla 1.0.6. Il comportamento di default (`ALL`) si attiva solo quando `publishedGt` supera i 30 giorni; negli altri casi il filtro incrementale resta invariato.
