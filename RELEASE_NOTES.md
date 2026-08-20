# Release Notes

## 2.0.1 — 2026-08-19

Release di manutenzione: correzione della violazione del vincolo di unicità `UNIQUE_FR_1` all'acquisizione di una nuova revisione di un flusso già presente, allineamento all'ecosistema GovPay 2.0.4 e runtime Docker su JDK 25.

### Correzioni — Violazione `UNIQUE_FR_1` su nuova revisione
L'acquisizione di una **nuova revisione** di un flusso già presente falliva con violazione del vincolo `UNIQUE_FR_1 (cod_dominio, cod_flusso, data_ora_flusso)` (es. `ORA-00001` su Oracle, *duplicate key* su PostgreSQL). pagoPA **incrementa la revisione mantenendo invariata la `data_ora_flusso`**, mentre `UNIQUE_FR_1` — che è la chiave usata dalle API GovPay per la **GET puntuale** `/flussiRendicontazione/{idDominio}/{idFlusso}/{dataOraFlusso}` — deve restare univoca. La gestione precedente (`marcaObsoleti`, introdotta con la #34) impostava solo il flag `obsoleto=true` sulle righe precedenti **senza modificarne la `data_ora_flusso`**: il successivo `INSERT` della nuova revisione ricadeva sulla stessa tripla e violava il vincolo.

Ora, in `FdrPaymentsWriter`, prima di inserire la nuova revisione le righe precedenti dello stesso flusso (`cod_dominio, cod_flusso, cod_psp`) vengono marcate obsolete e la loro `data_ora_flusso` viene **spostata indietro di 1 ms**, liberando lo slot per la nuova revisione che conserva la `data_ora_flusso` reale (quella interrogata dalle API). Lo spostamento avviene in ordine di `data_ora_flusso` **crescente** con **flush immediato riga per riga**, per evitare collisioni transienti sul vincolo unique (verificato dal DB ad ogni statement) e garantire l'ordine corretto rispetto all'`INSERT` finale. Con N revisioni successive lo scalamento è progressivo (`-1ms`, `-2ms`, ...). Il fix è **DB-agnostico** (shift calcolato in Java, non via SQL nativo per DBMS). *(Porting da 1.1.8.)*

- Rimosso il metodo di repository `marcaObsoleti` (query bulk `@Modifying`, che non poteva aggiornare la `data_ora_flusso` riga per riga), sostituito da `findByCodDominioAndCodFlussoAndCodPspOrderByDataOraFlussoAsc`.
- La marcatura non è più condizionata a `revisione > 1`: vengono considerate tutte le righe presenti per la chiave del flusso.
- Le vecchie revisioni restano consultabili come storico (`obsoleto=true`) ma con `data_ora_flusso` "sintetica" (spostata di N ms): la GET puntuale sulla data reale del flusso restituisce sempre l'**ultima revisione** (comportamento atteso).

### Docker
- Immagini portate al **runtime JDK 25**: base image aggiornata da `eclipse-temurin:21-jre-alpine` a `eclipse-temurin:25-jre-alpine` in `docker/govpay-fdr/Dockerfile.github` e `Dockerfile.daFile`. Cambia **solo il runtime**: il target di compilazione resta **Java 21**. Verificata l'esecuzione dell'intera suite di test su OpenJDK 25.0.3 LTS.

### Sicurezza
Nessuna nuova vulnerabilità da correggere in questa release. Formalizzata la gestione dei **falsi positivi** del report OWASP Dependency-Check: aggiunti i file di soppressione sotto `src/main/resources/owasp/falsePositives/` (un file per CVE, come negli altri progetti GovPay), collegati al plugin `dependency-check-maven` tramite la property `owasp.falsePositives.dir`.
- **CVE-2026-14683**, **CVE-2026-14686** (HdrHistogram 2.2.2, transitiva da `govpay-common` → `micrometer-core`): nessuna versione con il fix pubblicata, segnalazioni *disputed*/*Deferred* su NVD, CVSS 3.3 LOW con vettore `AV:L`. Da rivalutare all'uscita di una release corretta di HdrHistogram.
- **CVE-2018-14335** (H2 2.4.240): falso positivo, riguarda la funzione di backup di H2 **1.4.197 e precedenti**; H2 è in scope `provided` ed esclusa dall'assembly di distribuzione.

### Aggiornamenti dipendenze
- `govpay-bom` aggiornato da **2.0.1** a **2.0.4** (parent BOM).
- `govpay-common` aggiornato da **2.0.0** a **2.0.3**.
- Nel `pom.xml` non restano riferimenti a versioni `SNAPSHOT`.

### Test
Suite completa verde: **273 test, 0 failures, 0 errors, 0 skipped**.
- Aggiornati i test sulle revisioni alla nuova logica di spostamento (mock su `findByCodDominioAndCodFlussoAndCodPspOrderByDataOraFlussoAsc` al posto di `marcaObsoleti`).
- Aggiunto `testTreRevisioniStessoFlussoSpostamentoProgressivo`: scenario a 3 revisioni sullo stesso flusso (rev1 `T-1ms`, rev2 `T` → rev3 `T`), verifica lo shift progressivo (`rev1 → T-2ms`, `rev2 → T-1ms`), il flag obsoleto e la conservazione della `data_ora_flusso` reale sulla nuova revisione.

### Compatibilità
Nessuna breaking change: aggiornamento **drop-in** rispetto alla 2.0.0. Il vincolo `UNIQUE_FR_1` resta invariato e non sono richieste migrazioni dello schema dati né modifiche alle configurazioni applicative. Il runtime delle immagini Docker passa a JDK 25; per le installazioni non containerizzate resta supportato Java 21.

## 2.0.0 — 2026-07-11

Major di piattaforma: migrazione a **Spring Boot 4.x / Spring Framework 7.x** (Spring Batch 6, Hibernate ORM 7, Jackson 3), con allineamento all'ecosistema GovPay 2.0.

### Correzioni — API pagoPA (`publishedGt`)
L'API pagoPA `getAllPublishedFlows` restituisce **HTTP 400** (`FDR-1000`, *"The date cannot be older than 30 days"*) quando `publishedGt` è più vecchia di 30 giorni. Il batch calcola `publishedGt` come massima data di pubblicazione dei flussi già acquisiti per il dominio: per i domini con ultima acquisizione oltre tale finestra la chiamata falliva. Introdotta una gestione **configurabile** in `FdrApiService.getAllPublishedFlows`, con nuove proprietà (prefix `govpay.batch`):
- `govpay.batch.published-gt-max-age-days` (default `30`): soglia oltre la quale `publishedGt` è fuori finestra;
- `govpay.batch.published-gt-stale-strategy` (default `ALL`): `ALL` non invia `publishedGt` (recupera tutti i flussi; il dedup in `FdrHeadersWriter` evita ri-acquisizioni) oppure `CLAMP` (riporta la data a `adesso - max-age-days`).

Fix già rilasciato nella linea 1.0.x (v1.0.7); su `main` le proprietà risiedono in `BatchProperties`/`govpay.batch`.

### Correzioni — Quadratura importi (falso stato ANOMALA)
Il controllo di quadratura confrontava la somma degli importi rendicontati con il totale di testata in **virgola mobile (`double`)** con confronto stretto (`!=`). La somma accumula errori di arrotondamento binario (es. `0.10 + 0.20 = 0.30000000000000004`), generando una falsa discrepanza e lo stato `ANOMALA` (anomalia `007106`) anche a importi coincidenti — con conseguente mancata lettura del flusso dalle applicazioni client. Ora i confronti importo avvengono in **`BigDecimal` a 2 decimali** (`HALF_UP`): somma accumulata in `BigDecimal` per la quadratura (`007106`) e helper `importiDiversi()` per le rendicontazioni (`007104`, `007112`). Test di regressione aggiunto. *(Porting da 1.1.7.)*

### Correzioni — Precisione timestamp (`dataOraFlusso`)
I timestamp pagoPA (`OffsetDateTime` a precisione nanosecondo) venivano scritti su `fr`/`fr_temp` mantenendo i microsecondi; le API REST del backoffice GovPay serializzano a precisione **millisecondo**, quindi il GET puntuale `/flussiRendicontazione/{idDominio}/{idFlusso}/{dataOraFlusso}` (match esatto) non ritrovava la riga → **HTTP 404**. Aggiunto `.truncatedTo(ChronoUnit.MILLIS)` nei `convertToLocalDateTime(OffsetDateTime)` di `FdrHeadersProcessor` (step 2) e `FdrMetadataProcessor` (step 3). *(Porting da 1.1.7.)*

### Sicurezza
- **logback 1.5.35** (via `govpay-bom` 2.0.1): risolve `GHSA-jhq6-gfmj-v8fx` (CVSS 2.9) presente nella 1.5.34. logback è gestita centralmente dal BOM, senza override locali nel progetto.
- **Tomcat embedded 11.0.x** (fornito da Spring Boot 4): non più affetto dalle 7 vulnerabilità della 10.1.54 (3 Critical, 3 High, 1 Low); rimosso l'override `tomcat.version=10.1.55` introdotto nella 1.1.5, ora superfluo.
- **PostgreSQL JDBC 42.7.11 → 42.7.13** (override `postgresql.version`): risolve `GHSA-j92g-9f8w-j867` (CVSS 8.2), fissata in `42.7.12`; adottata la `42.7.13`. Il `govpay-bom` 2.0.1 pinna ancora la `42.7.11`, quindi override locale.

### Aggiornamenti dipendenze
- `govpay-bom` aggiornato a **2.0.1** (parent BOM) — vedi *Sicurezza* per il fix logback.
- `govpay-common` aggiornato da `1.1.2` a **2.0.0**.
- Aggiornamenti transitivi: Spring Boot **4.1.x**, Spring Framework **7.0.x**, Spring Batch **6.0.x**, Hibernate ORM **7.x**, Jackson **3.x** (`tools.jackson`), Tomcat embedded **11.0.x**.
- Aggiunta la versione esplicita `${hibernate.version}` alla dipendenza `hibernate-jpamodelgen` (non più gestita dal BOM).

### Refactor (Spring Batch 6 / Spring Boot 4)
- Allineati i package Spring Batch 6: `Job`/`Step`/`JobExecution`/`StepExecution`/`JobInstance`/`JobParameters` nei sottopackage `.job`/`.step`/`.job.parameters`, listener in `.listener`, item API in `org.springframework.batch.infrastructure.item.*`, repeat API in `org.springframework.batch.infrastructure.repeat.*`, eccezioni di launch in `org.springframework.batch.core.launch.*`, `Partitioner` in `org.springframework.batch.core.partition`. `JobParametersInvalidException` → `InvalidJobParametersException`; `@EntityScan` in `org.springframework.boot.persistence.autoconfigure`.
- `BatchController`: iniettato `JobRepository` al posto di `JobExplorer` (rimosso dall'API pubblica in Spring Batch 6).
- `BatchInfraConfig`: `JobConcurrencyService` costruito col solo `JobRepository`; `JobExecutionHelper` costruito con `JobOperator` al posto di `JobLauncher`.
- `BatchExecutionRecapListener`: usa `getJobInstanceId()` (rimosso `JobExecution.getJobId()`).
- **Migrazione completa a Jackson 3** (`tools.jackson`). Rimosse dal pom le dipendenze Jackson 2 (`jackson-databind`, `jackson-datatype-jsr310`, `jackson-databind-nullable`): in compile/runtime restano solo `tools.jackson` 3.x e il modulo **condiviso** `jackson-annotations` (`com.fasterxml.jackson.annotation`), che `tools.jackson.databind` richiede e onora sui model del client generato.
  - `GdeService` → `tools.jackson.databind.ObjectMapper` (coerente con `AbstractGdeService`).
  - `WebConfig` → mapper globale (MVC + GDE) come `JsonMapperBuilderCustomizer` sul `JsonMapper` di Boot.
  - `FdrApiClientConfig.createPagoPAObjectMapper()` → `tools.jackson` `JsonMapper`; `FdrApiService` usa `JacksonJsonHttpMessageConverter` (Jackson 3).
  - `EventoFdrMapper` → `tools.jackson` `JsonMapper`.
  - Serializer/deserializer `OffsetDateTime`/`LocalDate` riscritti per Jackson 3 in `utils.jackson3` (formato fisso e fallback CET preservati); rimosse le versioni Jackson 2.
  - Client OpenAPI generato: i model usano solo `com.fasterxml.jackson.annotation` (compatibili con Jackson 3); via `.openapi-generator-ignore` (+`ignoreFileOverride`) esclusi i file data Jackson 2 (`RFC3339DateFormat`, `RFC3339InstantDeserializer`, `RFC3339JavaTimeModule`); `RFC3339DateFormat` fornito a mano in versione Jackson 3.
- `BatchTaskExecutorConfig`: estratto il bean `taskExecutor` in una configurazione dedicata senza dipendenze JPA, per evitare il ciclo di bean introdotto da Spring Boot 4 (`entityManagerFactoryBuilder` → bootstrapExecutor → `BatchJobConfiguration` → `transactionManager` → `entityManagerFactory`).

### Test
- Import aggiornati ai package Spring Batch 6 e costruttori di `JobExecution`/`JobInstance`/`StepExecution` adeguati alle nuove firme.
- `@MockBean` (rimosso in Spring Boot 4) sostituito con `@MockitoBean`.
- `application-test.properties`: `scheduler.initialDelayString` alto per evitare che il trigger `@Scheduled` si avvii in concorrenza con le esecuzioni manuali dei test.

### Compatibilità
Major release: richiede **Java 21** e l'ecosistema GovPay 2.0 (`govpay-bom` **2.0.1**, `govpay-common` **2.0.0**). **Non** è un aggiornamento drop-in rispetto alla 1.1.x: la migrazione a Spring Boot 4 / Spring Batch 6 comporta cambi di package e di API (vedi *Refactor*). Nessuna modifica alle configurazioni applicative o allo schema dati.

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
