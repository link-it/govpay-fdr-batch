# Release Notes

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
