# Release Notes

## 1.1.6 — 2026-07-14

Release di manutenzione: aggiornamenti di sicurezza (jackson-databind, logback) e gestione della finestra temporale accettata da pagoPA per il parametro `publishedGt`.

### Sicurezza
Aggiornate dipendenze vulnerabili gestite dal `govpay-bom` tramite override locale delle property nel `pom.xml` (nessuna release del `govpay-bom` sulla linea 1.1 le corregge):
- **jackson-databind `2.21.1` → `2.21.5`** (`jackson.version`): risolve tra le altre `GHSA-j3rv-43j4-c7qm` e `GHSA-rmj7-2vxq-3g9f` (CVSS 8.1), `GHSA-rcqc-6cw3-h962` (6.5), `GHSA-5hh8-q8hv-fr38`, `GHSA-9fxm-vc8v-hj55`, `GHSA-hgj6-7826-r7m5` (5.3) e `GHSA-5jmj-h7xm-6q6v` / CVE-2026-54515 (fissata in 2.21.5).
- **logback `1.5.28` → `1.5.35`** (`logback.version`): risolve `GHSA-jhq6-gfmj-v8fx` (CVSS 2.9) e `GHSA-p47f-322f-whfh` (1.2).

### Correzioni — API pagoPA (`publishedGt`)
L'API pagoPA `getAllPublishedFlows` restituisce **HTTP 400** (`FDR-1000`, *"The date cannot be older than 30 days"*) quando `publishedGt` è più vecchia di 30 giorni. Il batch calcola `publishedGt` come massima data di pubblicazione dei flussi già acquisiti per il dominio: per i domini con ultima acquisizione oltre tale finestra la chiamata falliva. Introdotta una gestione **configurabile** in `FdrApiService.getAllPublishedFlows`.

### Configurazione
Nuove proprietà (prefix `govpay.batch`):

| Proprietà | Default | Descrizione |
|---|---|---|
| `govpay.batch.published-gt-max-age-days` | `30` | Soglia in giorni oltre la quale `publishedGt` è fuori finestra. |
| `govpay.batch.published-gt-stale-strategy` | `ALL` | Strategia quando la data è fuori finestra: `ALL` o `CLAMP`. |

Strategie:
- **`ALL`** (default): non invia `publishedGt` (`null`) e recupera **tutti** i flussi pubblicati. Nessun flusso viene perso; il controllo di esistenza in `FdrHeadersWriter` (per `codDominio + codFlusso + psp + revisione`) evita ri-acquisizioni dei flussi già presenti in `FR`/`FR_TEMP`. Costo: la sola chiamata di elenco è più pesante finché il dominio resta "indietro".
- **`CLAMP`**: riporta `publishedGt` a `adesso - published-gt-max-age-days`, limitando l'elenco agli ultimi giorni consentiti. Elenco più corto, ma può non recuperare flussi pubblicati oltre la finestra e non ancora acquisiti.

### Test
- Aggiunti test in `FdrApiServiceGdeIntegrationTest` per le tre casistiche: fuori finestra con `ALL` (invia `null`), fuori finestra con `CLAMP` (riporta la data a ~adesso-30g), dentro finestra (data invariata).

### Compatibilità
Nessuna breaking change: aggiornamento drop-in rispetto alla 1.1.5. Il comportamento di default (`ALL`) si attiva solo quando `publishedGt` supera i 30 giorni; negli altri casi il filtro incrementale resta invariato.

## 1.1.5 — 2026-06-08

Release di sicurezza: aggiornamento di Tomcat embedded alla versione patchata.

### Sicurezza
- **Tomcat embedded `10.1.54` → `10.1.55`**: forzata la versione di `tomcat-embed-core`, `tomcat-embed-websocket` e `tomcat-embed-el` tramite override della property `tomcat.version` in `pom.xml`. La 10.1.54, ereditata transitivamente da Spring Boot via `spring-boot-starter-web`, presentava 7 vulnerabilità note (3 Critical, 3 High, 1 Low): `GHSA-h6fc-48rj-7qqh` e `GHSA-r29c-68gh-xp6x` (CVSS 9.8), `GHSA-5m62-pw8w-7w9f` (CVSS 9.1), `GHSA-5mp6-jrq3-r938` e `GHSA-gx5v-xp9w-j4cg` (CVSS 7.5), `GHSA-fv25-8xcx-gqjc` (CVSS 7.3), `GHSA-9m89-8frq-c98c` (CVSS 3.7).

### Compatibilità
Nessuna breaking change: aggiornamento patch-level. Aggiornamento drop-in rispetto alla 1.1.4.

## 1.1.4 — 2026-05-06

Release di manutenzione: aggiornamento dipendenze GovPay, allineamento del modello dati a `govpay-common` e potenziamento della pipeline di build/release.

### Aggiornamenti dipendenze
- `govpay-bom` aggiornato a **1.1.3** (parent BOM).
- `govpay-common` aggiornato da `1.0.0` a **1.1.2**.

### Codice
- Migrazione al modello dati centralizzato di `govpay-common`: rimossi `Applicazione` e `ApplicazioneRepository` locali, ora forniti da `govpay-common` (`ApplicazioneEntity` + `it.govpay.common.repository.ApplicazioneRepository`). Adeguato l'entity `Versamento` e i test relativi.
- `GdeService`: aggiunto override del nuovo metodo astratto `getConfigurazioneComponente(ComponenteEvento, Giornale)` introdotto in `AbstractGdeService`.

### Database
- Aggiunti script di svecchiamento delle tabelle Spring Batch (`spring-batch-cleanup.sql`) per tutti i DBMS supportati (PostgreSQL, MySQL, Oracle, SQL Server, HSQLDB). Eliminano le esecuzioni di job (COMPLETED, FAILED, STOPPED, ABANDONED) più vecchie di un numero configurabile di giorni (default 90), rispettando le foreign key.

### Pipeline
- **SBOM CycloneDX**: aggiunto job `sbom` che genera l'SBOM aggregato (formati `json` + `xml`, schema 1.6) tramite `cyclonedx-maven-plugin`. Eseguito su push su `main`/tag o su richiesta esplicita (`vars.FORCE_SBOM_JOB`); disattivabile con `vars.DISABLE_SBOM_JOB`. L'SBOM viene incluso nel ZIP `release-reports` sotto `reports/sbom/`.
- **OSV Scanner**: integrato nel job `build` per generare il report JSON delle vulnerabilità (`osv-report.json`); incluso nello ZIP `release-reports` sotto `reports/osv/`. Rimosso il job `osv-scan` reusable separato.
- **Cache OWASP Dependency-Check**: chiave basata sulla data giornaliera con skip dell'update NVD quando la cache è esatta (stessa giornata).
- **Workflow `refresh-owasp-db`**: aggiornamento notturno (cron 03:00 UTC) della cache NVD per ridurre la latenza dei job di build.
- **Reports ZIP unico**: tutti i report (OWASP, JaCoCo, OSV, licenze, SBOM) collezionati in `release-reports-<tag>.zip` allegato alla GitHub Release.
- **Bump action GitHub**: `actions/checkout` v4→v6, `actions/setup-java` v4→v5, `actions/upload-artifact` e `actions/download-artifact` v4→v7, `actions/cache` v4→v5.
- **Fix step Zip SQL files**: aggiunto `mkdir -p target` prima dello zip per creare la cartella nel job `release` (non c'è una build Maven precedente che la generi).

### Compatibilità
Nessuna breaking change a livello di API o configurazione. Aggiornamento drop-in rispetto alla 1.1.3.
