# Release Notes

## 1.1.9 — 2026-10-07

Release di manutenzione: backport sulla linea 1.1.x del canale di acquisizione dei flussi di rendicontazione da **directory sul file system**, già presente sulla linea 2.x.

### Novità — Acquisizione da file system
Canale alternativo alle API pagoPA: i flussi depositati come file JSON in una directory vengono acquisiti all'inizio di ogni esecuzione del job, prima dell'interrogazione del Nodo.

```
cleanup -> [file system] -> headers -> metadata -> payments
```

È una **procedura di emergenza**, pensata per i flussi usciti dalla finestra di ricerca di pagoPA (30 giorni), per i tracciati forniti direttamente dal PSP o dall'ente e per sanare disallineamenti senza interventi manuali sul database. Riprende la funzionalità che GovPay aveva sui tracciati XML in `<resourceDir>/input/fr`.

Il formato accettato è la response della GET puntuale sul flusso (`/organizations/{organizationId}/fdrs/{fdr}/revisions/{revision}/psps/{pspId}`) con in più la lista dei pagamenti inline nel campo `payments`, cioè l'unione dei due endpoint pagoPA. Le date sono accettate sia come stringa ISO sia come secondi dall'epoch. L'XML legacy `ctFlussoRiversamento` non è supportato.

La persistenza riusa `FdrPaymentsWriter`: riconciliazione con i pagamenti, controlli di quadratura, anomalie e gestione delle revisioni sono gli stessi del canale API, quindi un flusso acquisito da file e uno scaricato dalle API producono gli stessi dati. Il flusso non passa da `FR_TEMP`.

### Configurazione
Disabilitato di default: con `govpay.fdr.input.enabled=false` lo step non entra nel job e la sequenza degli step resta quella storica.

```properties
govpay.fdr.input.enabled=true
govpay.fdr.input.dir=/var/govpay/fdr/input
govpay.fdr.input.processed-dir=/var/govpay/fdr/processed   # default <dir>/processed
govpay.fdr.input.error-dir=/var/govpay/fdr/error           # default <dir>/error
govpay.fdr.input.extension=.json
govpay.fdr.input.max-files-per-run=1000
```

Lo step è **inerte** quando non c'è nulla da elaborare — directory non configurata, inesistente o vuota: non produce item, non crea directory e non fallisce. Il canale di emergenza non può quindi bloccare l'acquisizione ordinaria verso pagoPA. Le directory di archiviazione nascono solo quando c'è un file da archiviare.

### Ciclo di vita dei file
Un file non viene mai cancellato. Ogni file è persistito in una transazione propria, quindi un errore su un file non ferma gli altri.

| Esito | Destinazione |
|---|---|
| Flusso acquisito | `processed-dir`, col nome originale |
| Flusso già presente in `FR` | `processed-dir`, senza reinserimento |
| File malformato, dominio non censito o non abilitato, scrittura fallita | `error-dir`, con un `.error.txt` contenente la motivazione |

**Multi-nodo**: la directory può essere condivisa. Ogni nodo prende in carico un file rinominandolo in `<nome>.<cluster-id>.processing` con una move atomica; se la rinomina fallisce il file è di un altro nodo e viene ignorato. Un file rimasto con quel suffisso indica un nodo terminato durante l'elaborazione: è visibile all'operatore, che può rinominarlo per rimetterlo in coda.

**Tracciamento**: ogni file produce un evento GDE `ACQUISIZIONE_FLUSSO_FILE_SYSTEM` di categoria `INTERNO`, con esito `OK` per gli acquisiti e i duplicati e `KO` per gli scarti.

### Correzioni — Deserializzazione delle date
`OffsetDateTimeDeserializer` accetta ora anche gli istanti scritti come **secondi dall'epoch** con parte frazionaria (es. `1786109246.000000000`), oltre alle stringhe ISO già gestite. Prima un valore numerico finiva silenziosamente a `null`: le API pagoPA serializzano gli `Instant` come stringa, ma i tracciati depositati su file system possono arrivare da esportazioni che li scrivono come numero.

### Note sul backport
Il codice è adattato alla linea 1.1.x (Spring Boot 3 / Spring Batch 5, Jackson 2): package `org.springframework.batch.item.*`, `ObjectMapper` al posto di `JsonMapper` e, nel processor, una catch dedicata a `JsonProcessingException` — che essendo sottoclasse di `IOException` verrebbe altrimenti segnalata come errore di lettura invece che di parsing.

### Sicurezza
Risolte le segnalazioni OSV sulla linea 1.1.x: **36 vulnerabilità note su 15 pacchetti** (5 Critical, 14 High, 15 Medium, 2 Low), tutte transitive della catena di BOM gestita dal `govpay-bom` 1.1.3.

| Dipendenza | Da | A |
|---|---|---|
| `spring-boot` | 3.5.14 | **3.5.16** |
| `jackson` | 2.21.5 | **2.21.7** |
| `tomcat-embed` | 10.1.55 | **10.1.60** |
| `log4j2` | 2.25.4 | **2.25.5** |
| `spring-framework` | 6.2.18 | **6.2.19** |
| `micrometer` | 1.15.11 | **1.15.12** |
| `httpclient5` | 5.5.2 | **5.6.4** |
| `httpcore5` / `httpcore5-h2` | 5.3.6 | **5.4.4** |
| `spring-retry` | 2.0.12 | **2.0.13** |
| `spring-data-commons` | 3.5.11 | **3.5.13** (via Spring Boot) |

Fra le chiuse, tre critiche su `tomcat-embed-core`: `GHSA-9xv2-5v5q-p794` (CVSS 9.8), `GHSA-gcx9-497g-6cp6` e `GHSA-h3x4-894j-xpx5` (9.1).

Per `spring-framework`, `micrometer`, `httpclient5`, `httpcore5` e `spring-retry` non bastava alzare le property del parent: nel `govpay-bom` `spring-boot-dependencies` è importato **prima** di `spring-framework-bom`, e fra import di BOM vince il primo dichiarato. Le versioni sono perciò fissate in un `dependencyManagement` locale, che ha la precedenza sugli import ereditati. `spring-data` non è pinnato di proposito: il treno `2025.1.x` è Spring Data 4.0, che richiede Spring Framework 7 e non è compatibile con questa linea.

### Vulnerabilità residue
`GHSA-j9f9-w8pj-32f8` e `GHSA-pc63-qcmh-9cmg` su `spring-webmvc`, entrambe **CVSS 9.8**, non hanno una versione correttiva su questa linea: per il ramo 6.2 OSV riporta `last_affected = 6.2.19` — l'ultima release 6.2.x disponibile è ancora affetta — e `fixed = 7.0.9`, su Spring Framework 7, che richiede Spring Boot 4 (linea 2.x).

Nessuna delle due è raggiungibile da questa applicazione, che espone quattro GET JSON sotto `/api/batch` più gli endpoint actuator:

- `GHSA-j9f9-w8pj-32f8` — corruzione di stream Server Sent Event nel rendering di fragment: non è usato alcun `SseEmitter` né `text/event-stream`.
- `GHSA-pc63-qcmh-9cmg` — *improper path limitation* in `XsltView`: non è usato `XsltView` né alcun `ViewResolver`.

Sono sospese in `osv-scanner.toml` con `ignoreUntil = 2027-01-31`, così la soppressione scade e torna in valutazione. Rimossa invece la soppressione di `GHSA-5jmj-h7xm-6q6v`, scaduta e ora superata da jackson 2.21.7.

### Compatibilità
Nessuna breaking change: aggiornamento drop-in rispetto alla 1.1.8. A canale disabilitato (default) il comportamento del batch è invariato. Nessuna modifica allo schema del database. Gli aggiornamenti di sicurezza restano all'interno delle rispettive linee (Spring Boot 3.5.x, Spring Framework 6.2.x, Spring Data 3.5.x).

## 1.1.8 — 2026-07-28

Release di manutenzione: correzione della violazione del vincolo di unicità `UNIQUE_FR_1` all'acquisizione di una nuova revisione di un flusso già presente.

### Correzioni — Violazione `UNIQUE_FR_1` su nuova revisione
L'acquisizione di una **nuova revisione** di un flusso già presente falliva con violazione del vincolo `UNIQUE_FR_1 (cod_dominio, cod_flusso, data_ora_flusso)` (es. `ORA-00001` su Oracle, *duplicate key* su PostgreSQL). pagoPA **incrementa la revisione mantenendo invariata la `data_ora_flusso`**, mentre `UNIQUE_FR_1` — che è la chiave usata dalle API GovPay per la **GET puntuale** `/flussiRendicontazione/{idDominio}/{idFlusso}/{dataOraFlusso}` — deve restare univoca. La gestione precedente (`marcaObsoleti`) impostava solo il flag `obsoleto=true` sulle righe precedenti **senza modificarne la `data_ora_flusso`**: il successivo `INSERT` della nuova revisione ricadeva sulla stessa tripla e violava il vincolo.

Ora, in `FdrPaymentsWriter`, prima di inserire la nuova revisione le righe precedenti dello stesso flusso (`cod_dominio, cod_flusso, cod_psp`) vengono marcate obsolete e la loro `data_ora_flusso` viene **spostata indietro di 1 ms**, liberando lo slot per la nuova revisione che conserva la `data_ora_flusso` reale (quella interrogata dalle API). Lo spostamento avviene in ordine di `data_ora_flusso` **crescente** con **flush immediato riga per riga**, per evitare collisioni transienti sul vincolo unique (verificato subito dal DB) e garantire l'ordine corretto rispetto all'`INSERT` finale. Il fix è **DB-agnostico** (shift calcolato in Java, non via SQL nativo per DBMS).

### Note
- Le vecchie revisioni restano consultabili come storico (`obsoleto=true`) ma con `data_ora_flusso` "sintetica" (spostata di N ms): la GET puntuale sulla data reale del flusso restituisce sempre l'**ultima revisione** (comportamento atteso).
- Rimosso il metodo di repository `marcaObsoleti` (query bulk `@Modifying`), sostituito da `findByCodDominioAndCodFlussoAndCodPspOrderByDataOraFlussoAsc`.

### Test
- Aggiornati i test `ObsoletoRevisioneTests` alla nuova logica di spostamento.
- Aggiunto `testTreRevisioniStessoFlussoSpostamentoProgressivo`: scenario a 3 revisioni sullo stesso flusso (rev1 `T-1ms`, rev2 `T` → rev3 `T`), verifica shift progressivo (`rev1 → T-2ms`, `rev2 → T-1ms`), flag obsoleto e conservazione della `data_ora_flusso` reale sulla nuova revisione.

### Compatibilità
Nessuna breaking change: aggiornamento drop-in rispetto alla 1.1.7. Il vincolo `UNIQUE_FR_1` resta invariato.

## 1.1.7 — 2026-07-23

Release di manutenzione: correzioni sui flussi di rendicontazione (quadratura importi e precisione dei timestamp) e aggiornamento di sicurezza del driver PostgreSQL.

### Sicurezza
- **PostgreSQL JDBC `42.7.11` → `42.7.13`** (override `postgresql.version`): risolve `GHSA-j92g-9f8w-j867` (CVSS 8.2), fissata in `42.7.12`; adottata la `42.7.13` (ultima patch della linea 42.7.x). Il `govpay-bom` 1.1.x pinna ancora la `42.7.11`, quindi override locale.

### Correzioni — Quadratura importi (falso stato ANOMALA)
Il controllo di quadratura confrontava la somma degli importi rendicontati con il totale di testata in **virgola mobile (`double`)** con confronto stretto (`!=`). La somma di più importi accumula errori di arrotondamento binario (es. `0.10 + 0.20 = 0.30000000000000004`), generando una **falsa discrepanza** e lo stato `ANOMALA` (anomalia `007106`) anche quando gli importi coincidono (es. `[3.703,07]` vs `[3.703,07]`) — con conseguente mancata lettura del flusso da parte delle applicazioni client. Ora tutti i confronti importo avvengono in **`BigDecimal` a 2 decimali** (`HALF_UP`): somma accumulata in `BigDecimal` per la quadratura (`007106`) e helper `importiDiversi()` per i confronti delle rendicontazioni (`007104` pagato, `007112` revoca). Aggiunto test di regressione.

### Correzioni — Precisione timestamp (`dataOraFlusso`)
I timestamp restituiti da pagoPA FDR (`OffsetDateTime` a precisione nanosecondo) venivano convertiti in `LocalDateTime` **senza troncamento** e scritti su `fr`/`fr_temp` mantenendo i microsecondi (es. `2026-07-22 03:12:54.185108`). Le API REST del backoffice GovPay serializzano la `dataOraFlusso` a precisione **millisecondo** (`yyyy-MM-dd'T'HH:mm:ss.SSSZ`), quindi il GET puntuale `/flussiRendicontazione/{idDominio}/{idFlusso}/{dataOraFlusso}` — che fa match esatto sulla colonna — non ritrovava la riga (`.185` ms ≠ `.185108` µs su PostgreSQL) → **HTTP 404**. Fix nei due `convertToLocalDateTime(OffsetDateTime)` di `FdrHeadersProcessor` (step 2) e `FdrMetadataProcessor` (step 3): aggiunto `.truncatedTo(ChronoUnit.MILLIS)`; copre tutti i timestamp valorizzati dai converter (`dataOraFlusso`, `dataOraPubblicazione`, `dataRegolamento`, `dataOraAggiornamento`). Per i dati storici è disponibile un `UPDATE` una-tantum lato GovPay (`date_trunc('milliseconds', data_ora_flusso)`).

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
