<p align="center">
<img src="https://www.link.it/wp-content/uploads/2025/01/logo-govpay.svg" alt="GovPay Logo" width="200"/>
</p>

# GovPay - Porta di accesso al sistema pagoPA - FDR Batch

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=link-it_govpay-fdr-batch&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=link-it_govpay-fdr-batch)
[![Docker Hub](https://img.shields.io/docker/v/linkitaly/govpay-fdr-batch?label=Docker%20Hub&logo=docker)](https://hub.docker.com/r/linkitaly/govpay-fdr-batch)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://raw.githubusercontent.com/link-it/govpay-fdr-batch/main/LICENSE)
[![Spring Boot](https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fraw.githubusercontent.com%2Flink-it%2Fgovpay-bom%2Fmain%2Fpom.xml&query=%2F%2F*%5Blocal-name()%3D'spring-boot.version'%5D&label=Spring%20Boot&color=brightgreen)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

Batch Spring Boot che acquisisce da pagoPA i Flussi di Rendicontazione (FDR) degli Enti Creditori, li salva nel database di GovPay e li riconcilia con i pagamenti.

## Sommario

Il batch esegue un job Spring Batch, `fdrAcquisitionJob`, sul database di GovPay. Per ogni dominio con l'acquisizione dei flussi abilitata (`domini.scarica_fr`) interroga il servizio FDR di pagoPA (API `organizations`), scarica i flussi pubblicati dopo l'ultimo già acquisito, salva testata (`fr`) e singoli pagamenti (`rendicontazioni`) e li collega ai pagamenti e alle pendenze di GovPay, segnalando le anomalie di quadratura.

| Step | Cosa fa |
|------|---------|
| `cleanupStep` | Svuota la tabella di appoggio `fr_temp` |
| `fdrFileSystemAcquisitionStep` | Solo con `govpay.fdr.input.enabled=true`: acquisisce i flussi depositati come file JSON in una directory (vedi [Acquisizione da file system](#acquisizione-da-file-system)) |
| `fdrHeadersAcquisitionStep` | Per ogni dominio legge l'elenco dei flussi pubblicati (`GET /organizations/{organizationId}/fdrs?publishedGt=...`, con paginazione) e salva in `fr_temp` quelli non ancora acquisiti; domini in parallelo |
| `fdrMetadataAcquisitionStep` | Per ogni flusso in `fr_temp` scarica la testata (`GET .../fdrs/{fdr}/revisions/{revision}/psps/{pspId}`); una partizione per dominio |
| `fdrPaymentsAcquisitionStep` | Scarica i pagamenti del flusso (`GET .../payments`, con paginazione), salva `fr` e `rendicontazioni`, esegue riconciliazione e controlli e rimuove il flusso da `fr_temp`; una partizione per dominio |

Il connettore verso pagoPA (URL, autenticazione, timeout) non è una proprietà del batch: si ricava dal dominio seguendo la catena dominio → stazione → intermediario → connettore indicato in `intermediari.cod_connettore_fr`, configurato in GovPay (tabella `connettori`). Ogni chiamata a pagoPA è registrata nel Giornale degli Eventi.

Il job si avvia da uno scheduler interno (modalità di default), una sola volta con il profilo `cron`, oppure a richiesta con l'API REST descritta in [Avvio manuale via REST](#avvio-manuale-via-rest). Le esecuzioni sono coordinate tra più nodi tramite i metadati Spring Batch sul database.

Storico delle modifiche nel [ChangeLog](ChangeLog).

## Integrazione con GovPay

| Componente | Quando serve | Obbligatorio | Ruolo |
|------------|--------------|--------------|-------|
| Database di GovPay | runtime | sì | Legge `domini`, `stazioni`, `intermediari`, `connettori`, `configurazione`, `pagamenti`, `singoli_versamenti`, `versamenti`; scrive `fr` e `rendicontazioni` (e marca obsolete le revisioni precedenti di un flusso in `fr`). Le tabelle sono create dagli script di installazione di GovPay, il batch non crea lo schema |
| Tabella `fr_temp` | runtime | sì | Tabella di appoggio del batch, svuotata a ogni esecuzione. Non fa parte dello schema di GovPay: si crea con `sql/<database>/create.sql` di `sql.zip` (vedi [Schema del database](#schema-del-database)) |
| Tabelle Spring Batch | runtime | sì | Metadati delle esecuzioni (`batch_job_*`, `batch_step_*`), usati anche per evitare esecuzioni concorrenti tra nodi. Lo schema è in `sql.zip` e l'immagine Docker può crearlo all'avvio |
| [govpay-bom](https://github.com/link-it/govpay-bom) | build | sì | Parent Maven: versioni delle dipendenze e dei plugin, esecuzione OWASP `check-owasp`, profilo `dist` |
| [govpay-common](https://github.com/link-it/govpay-common) | build | sì | Libreria comune: connettori, entità di anagrafica, coordinamento dei job, API REST del batch, client GDE, transaction id e livelli di log da database, script di svecchiamento dei metadati Spring Batch |
| Servizio FDR di pagoPA | runtime | sì | API `organizations` del servizio FDR (client generato da `src/main/resources/openapi/fdr_organization.json`). URL e credenziali sono nel connettore FDR dell'intermediario |
| [govpay-gde-api](https://github.com/link-it/govpay-gde-api) | runtime | no | Riceve gli eventi delle chiamate a pagoPA e delle acquisizioni da file system (`POST /eventi`), se il connettore `govpay_gde_api` è abilitato in GovPay. Un GDE non raggiungibile non blocca il batch |
| [govpay-notify-batch](https://github.com/link-it/govpay-notify-batch) | runtime | no | Notifica alle applicazioni dell'Ente le rendicontazioni acquisite da questo batch che non hanno un pagamento in GovPay |
| GovPay (monolite) e [govpay-console-api](https://github.com/link-it/govpay-console-api) | runtime | no | Leggono i flussi acquisiti (`fr`, `rendicontazioni`) per la riconciliazione e la consultazione dalla console |

Le modifiche ai livelli di log fatte dalla console (riga `log_level` della tabella `configurazione`) raggiungono il batch entro l'intervallo di refresh, senza riavvio.

## Compilazione

Requisiti: JDK 21 e Maven 3.8.1 o successivo.

```bash
mvn clean install -P jar     # fat jar, per esecuzione standalone e container (profilo di default)
mvn clean install -P war     # war, per il deploy su application server
```

Il profilo sceglie il packaging; il risultato è in `target/govpay-fdr-batch.jar` o `target/govpay-fdr-batch.war`. In entrambi i casi i driver JDBC non sono inclusi. La build esegue i test e l'analisi OWASP Dependency-Check delle dipendenze (esecuzione `check-owasp` di govpay-bom); per escluderla, per esempio in sviluppo:

```bash
mvn clean install -P jar -Dowasp.phase=none
```

Per produrre anche `target/sql.zip`, con lo schema Spring Batch per ogni database, gli script della tabella `fr_temp` e gli script di svecchiamento dei metadati, usato dall'immagine Docker e allegato alle release:

```bash
mvn clean install -Pjar,dist
```

Il profilo `jar` va indicato insieme a `dist`: è quello che lascia i driver JDBC fuori dal jar.

## Configurazione

Le proprietà si impostano in `application.properties`, da riga di comando (`-D<proprietà>=<valore>`) o con le variabili d'ambiente equivalenti di Spring Boot: punti e trattini diventano `_` e il nome va in maiuscolo (es. `govpay.batch.page-size` → `GOVPAY_BATCH_PAGE_SIZE`, `scheduler.fdrAcquisitionJob.fixedDelayString` → `SCHEDULER_FDRACQUISITIONJOB_FIXEDDELAYSTRING`). Con l'immagine Docker si usano anche le variabili descritte in [Esecuzione con Docker](#esecuzione-con-docker).

### Acquisizione da pagoPA

| Proprietà | Default | Descrizione |
|-----------|---------|-------------|
| `govpay.batch.thread-pool-size` | `5` | Domini elaborati in parallelo negli step headers, metadata e payments |
| `govpay.batch.headers-chunk-size` | `1` | Domini elaborati per transazione nello step headers |
| `govpay.batch.metadata-chunk-size` | `100` | Flussi elaborati per transazione nello step metadata |
| `govpay.batch.payments-chunk-size` | `50` | Flussi elaborati per transazione nello step payments |
| `govpay.batch.page-size` | `1000` | Elementi per pagina nelle richieste paginate a pagoPA |
| `govpay.batch.max-retries` | `3` | Tentativi complessivi di una chiamata a pagoPA che fallisce per errore HTTP o di rete, con attesa crescente tra un tentativo e l'altro (2 s, 4 s, ..., al massimo 10 s) |
| `govpay.batch.published-gt-max-age-days` | `30` | Età massima, in giorni, del parametro `publishedGt` accettata da pagoPA (oltre risponde 400 `FDR-1000`) |
| `govpay.batch.published-gt-stale-strategy` | `ALL` | Se la data dell'ultimo flusso acquisito per un dominio è più vecchia del limite: `ALL` non invia `publishedGt` e recupera tutti i flussi pubblicati; `CLAMP` la riporta a oggi meno `published-gt-max-age-days` |
| `govpay.batch.skip-limit` | `10` | Letta in `BatchProperties` ma non usata: lo step headers salta ogni dominio in errore senza limite |
| `scheduler.fdrAcquisitionJob.fixedDelayString` | `7200000` | Intervallo tra la fine di un'esecuzione e l'avvio della successiva, in ms (2 ore) |
| `scheduler.initialDelayString` | `1` | Ritardo della prima esecuzione dopo l'avvio, in ms |
| `govpay.batch.enabled` | `true` | Letta in `BatchProperties` ma non usata: per non schedulare il job usare il profilo `cron` o un profilo diverso da `default` |

### Acquisizione da file system

| Proprietà | Default | Descrizione |
|-----------|---------|-------------|
| `govpay.fdr.input.enabled` | `false` | Aggiunge al job lo step di acquisizione da file system |
| `govpay.fdr.input.dir` | - | Directory da cui leggere i flussi; se non è impostata o non esiste lo step non fa nulla |
| `govpay.fdr.input.processed-dir` | `<dir>/processed` | Destinazione dei file acquisiti o già presenti in `fr` |
| `govpay.fdr.input.error-dir` | `<dir>/error` | Destinazione dei file scartati, con un `.error.txt` che ne spiega il motivo |
| `govpay.fdr.input.extension` | `.json` | Estensione dei file da acquisire |
| `govpay.fdr.input.max-files-per-run` | `1000` | File elaborati al massimo in un'esecuzione del job |

### Esecuzione e cluster

| Proprietà | Default | Descrizione |
|-----------|---------|-------------|
| `govpay.batch.cluster-id` | `GovPay-FDR-Batch` | Identificativo del nodo, registrato nelle esecuzioni e negli eventi GDE e usato per prendere in carico i file; nel container è l'indirizzo IP |
| `govpay.batch.stale-threshold-minutes` | `120` | Minuti senza aggiornamenti dopo cui un'esecuzione in corso è considerata bloccata e può essere superata |
| `govpay.batch.job-repository.jdbc.enabled` | `true` | Metadati Spring Batch su database (da `govpay-common`); senza, il coordinamento tra nodi non funziona |
| `spring.batch.job.enabled` | `false` | Avvio automatico dei job da parte di Spring Boot: resta `false`, i job sono avviati dallo scheduler o dal profilo `cron` |
| `spring.profiles.active` | `default` | `cron` per eseguire il job una volta e terminare (vedi [Esecuzione](#esecuzione)) |
| `server.port` | `8080` (`10001` nel container) | Porta HTTP dell'API REST del batch e degli endpoint actuator |
| `spring.application.name` | `govpay-fdr-batch` | Nome dell'applicazione nei log |
| `spring.main.banner-mode` | `off` | Banner di Spring Boot all'avvio |
| `spring.jackson.default-property-inclusion` | `non_null` | Esclude i campi nulli dal JSON |
| `spring.jackson.time-zone` | `Europe/Rome` | Fuso orario dell'applicazione, delle date verso pagoPA e degli eventi GDE |

### Connettori e client HTTP

URL, autenticazione e timeout di pagoPA e del GDE non sono proprietà del batch: sono nei connettori di GovPay (tabella `connettori`), indicati da `intermediari.cod_connettore_fr` e, per il GDE, dal connettore `govpay_gde_api`.

| Proprietà | Default | Descrizione |
|-----------|---------|-------------|
| `govpay.client.cache.enabled` | `false` | Carica i connettori all'avvio e li tiene in cache; la cache si svuota con `GET /api/batch/cache/clear` |
| `govpay.client.async.core-pool-size` | `10` | Thread minimi per l'invio asincrono degli eventi al GDE |
| `govpay.client.async.max-pool-size` | `50` | Thread massimi per l'invio asincrono degli eventi al GDE |
| `govpay.client.async.queue-capacity` | `100` | Eventi in coda in attesa di invio |
| `govpay.client.async.thread-name-prefix` | `async-http-` | Prefisso dei thread di invio |

### Database

| Proprietà | Default | Descrizione |
|-----------|---------|-------------|
| `spring.datasource.url` | - | URL JDBC del database di GovPay (obbligatoria con il jar) |
| `spring.datasource.jndi-name` | - | Nome JNDI del datasource, per il deploy del war su application server (es. `java:comp/env/jdbc/govpay`); se impostata, le altre proprietà `spring.datasource.*` sono ignorate |
| `spring.datasource.username` | - | Utente del database |
| `spring.datasource.password` | - | Password del database |
| `spring.datasource.driver-class-name` | dedotto dall'URL | Classe del driver JDBC |
| `spring.jpa.database-platform` | dedotto dal driver | Dialetto Hibernate |
| `spring.jpa.properties.hibernate.dialect` | dedotto dal driver | Dialetto Hibernate |
| `spring.jpa.hibernate.ddl-auto` | `none` (`create-drop` con database embedded) | Gestione dello schema: lasciare `none` o `validate`, lo schema è di GovPay |
| `spring.datasource.hikari.minimum-idle` | `10` (`2` nel container) | Connessioni minime inattive del pool |
| `spring.datasource.hikari.maximum-pool-size` | `10` | Connessioni massime del pool |
| `spring.datasource.hikari.connection-timeout` | `30000` (`20000` nel container) | Attesa massima di una connessione, in ms |
| `spring.datasource.hikari.idle-timeout` | `600000` (`10000` nel container) | Tempo dopo cui una connessione inattiva viene chiusa, in ms |
| `spring.datasource.hikari.max-lifetime` | `1800000` (`600000` nel container) | Durata massima di una connessione, in ms |

I driver JDBC non sono inclusi né nel jar né nel war: vedi [Esecuzione](#esecuzione).

### Logging

| Proprietà | Default | Descrizione |
|-----------|---------|-------------|
| `logging.file.name` | - (solo console) | File di log; nel container `/var/log/govpay-fdr/govpay-fdr.log` |
| `logging.level.it.govpay` | `INFO` | Livello di log dei package GovPay |
| `logging.pattern.level` | `%5p [%X{transactionId:-},%X{correlationId:-}]` | Aggiunge a ogni riga transaction id e correlation id dell'esecuzione: il correlation id è quello della richiesta di avvio manuale, oppure generato per gli avvii schedulati |
| `govpay.logging.livelli-dinamici.enabled` | `true` | Livelli di log letti dalla riga `log_level` della tabella `configurazione` |
| `govpay.logging.livelli-dinamici.intervallo-refresh` | `60s` (variabile `LOG_LEVEL_REFRESH_INTERVAL`) | Intervallo di rilettura dei livelli dal database; `0` li legge solo all'avvio |
| `govpay.logging.livelli-dinamici.ritardo-iniziale` | `60s` | Ritardo della prima rilettura dopo quella di avvio |
| `govpay.logging.livelli-dinamici.logger-gestiti[0]` | `it.govpay` | Prefissi dei logger modificabili da database |
| `govpay.logging.tracciatura.enabled` | `true` | Transaction id e correlation id nelle richieste HTTP e nelle chiamate in uscita (da `govpay-common`) |
| `govpay.logging.tracciatura.correlation-inbound-headers` | `X-Correlation-ID,X-Request-ID` | Header letti in ingresso per il correlation id, in ordine di precedenza |

Le altre proprietà `govpay.logging.tracciatura.*` (lunghezza e formato degli identificativi ricevuti, header di risposta e verso i servizi chiamati) sono descritte nel [README di govpay-common](https://github.com/link-it/govpay-common#configurazione).

### Monitoraggio

| Proprietà | Default | Descrizione |
|-----------|---------|-------------|
| `management.server.port` | porta del servizio | Porta dedicata agli endpoint actuator |
| `management.endpoints.web.base-path` | `/actuator` | Base path degli endpoint actuator |
| `management.endpoints.web.exposure.include` | `health,info,metrics,prometheus` (variabile `MANAGEMENT_ENDPOINTS`) | Endpoint actuator esposti |
| `management.endpoint.health.show-details` | `when_authorized` | Dettagli dell'health: il servizio non ha autenticazione, quindi restituisce solo lo stato |
| `management.health.db.enabled` | `true` | Include la connessione al database nell'health |
| `management.metrics.tags.application` | `govpay-fdr-batch` | Tag che identifica il servizio nelle metriche |
| `govpay.metrics.enabled` | `false` | Metriche aggiuntive di `govpay-common` sulle chiamate ai servizi esterni e sui tempi delle richieste (`govpay.external.service.duration`, `govpay.api.*`) |

## Esecuzione

### Driver JDBC

I driver JDBC **non sono inclusi** nel fat jar e vanno forniti a runtime. Creare una directory (es. `jdbc-drivers/`) e copiarvi il driver del database utilizzato (versioni gestite da `govpay-bom`):

| Database   | Driver |
|------------|--------|
| PostgreSQL | [postgresql-42.7.14.jar](https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar) |
| MySQL      | [mysql-connector-j-26.10.0.jar](https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/26.10.0/mysql-connector-j-26.10.0.jar) |
| Oracle     | [ojdbc11-23.26.3.0.0.jar](https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.26.3.0.0/ojdbc11-23.26.3.0.0.jar) |
| SQL Server | [mssql-jdbc-13.6.1.jre11.jar](https://repo1.maven.org/maven2/com/microsoft/sqlserver/mssql-jdbc/13.6.1.jre11/mssql-jdbc-13.6.1.jre11.jar) |
| H2         | [h2-2.5.252.jar](https://repo1.maven.org/maven2/com/h2database/h2/2.5.252/h2-2.5.252.jar) |

### Schema del database

Prima del primo avvio, nel database di GovPay devono esistere:

- le tabelle dei metadati Spring Batch: script `sql/spring-batch/schema-<database>.sql` di `sql.zip`;
- la tabella `fr_temp`: script `sql/<database>/create.sql` di `sql.zip` (`postgresql`, `mysql`, `oracle`, `sqlserver`, `hsqldb`).

`sql/<database>/add-indexes.sql` aggiunge alle tabelle `fr`, `pagamenti` e `rendicontazioni` gli indici usati dalle query del batch; `drop.sql` e `delete.sql` rimuovono o svuotano `fr_temp`. Gli script in `sql/cleanup/` cancellano i metadati delle esecuzioni vecchie e vanno pianificati periodicamente. Con `GOVPAY_FDR_POP_DB_SKIP=FALSE` l'immagine Docker crea da sola le tabelle Spring Batch e `fr_temp`, ciascuna solo se manca.

### Jar standalone

Il jar usa `PropertiesLauncher`: la proprietà `loader.path` indica la directory dei driver JDBC. Configurazione minima per PostgreSQL, con lo scheduler interno:

```bash
java -Dloader.path=./jdbc-drivers \
     -Dspring.datasource.url=jdbc:postgresql://localhost:5432/govpay \
     -Dspring.datasource.username=govpay \
     -Dspring.datasource.password=<password> \
     -jar target/govpay-fdr-batch.jar
```

Modalità di esecuzione:

| Modalità | Come si attiva | Comportamento |
|----------|----------------|---------------|
| Scheduler interno | default (nessun profilo) | Il processo resta attivo, esegue il job subito e poi ogni `scheduler.fdrAcquisitionJob.fixedDelayString` ms; espone API REST e actuator |
| Esecuzione singola | `--spring.profiles.active=cron` | Esegue il job una volta e termina: la pianificazione è affidata a un cron esterno. L'exit code è `0` anche se il job fallisce o è già in esecuzione su un altro nodo, `1` solo se non si riesce a superare un'esecuzione bloccata: l'esito va letto nei log |

```bash
java -Dloader.path=./jdbc-drivers \
     -Dspring.datasource.url=jdbc:postgresql://localhost:5432/govpay \
     -Dspring.datasource.username=govpay \
     -Dspring.datasource.password=<password> \
     -jar target/govpay-fdr-batch.jar --spring.profiles.active=cron
```

### War su application server

Il war si produce con `mvn clean install -P war` (`target/govpay-fdr-batch.war`): non è allegato alle release né incluso nell'immagine Docker. Richiede un application server Jakarta EE 11 (Servlet 6.1), per esempio Tomcat 11.

Il war usa il datasource dell'application server, indicato con `spring.datasource.jndi-name`; il driver JDBC va nelle librerie dell'application server. Le altre proprietà si passano come proprietà di sistema della JVM o variabili d'ambiente. Esempio per Tomcat, con il datasource definito in `conf/context.xml`:

```xml
<Resource name="jdbc/govpay" auth="Container" type="javax.sql.DataSource"
          driverClassName="org.postgresql.Driver" url="jdbc:postgresql://localhost:5432/govpay"
          username="govpay" password="<password>" maxTotal="10"/>
```

```bash
export CATALINA_OPTS="-Dspring.datasource.jndi-name=java:comp/env/jdbc/govpay"
cp target/govpay-fdr-batch.war $CATALINA_BASE/webapps/
```

Il batch gira con lo scheduler interno; API REST e actuator rispondono sotto il context path del war (es. `http://localhost:8080/govpay-fdr-batch/actuator/health`).

## Esecuzione con Docker

L'immagine è pubblicata su Docker Hub come [linkitaly/govpay-fdr-batch](https://hub.docker.com/r/linkitaly/govpay-fdr-batch) (release) e `linkitaly/govpay-fdr-batch-dev` (sviluppo, a ogni push su `main`). Con lo scheduler interno il servizio risponde sulla porta `10001`; i driver JDBC si montano in `/opt/jdbc-drivers`.

```bash
docker run -d --name govpay-fdr-batch -p 10001:10001 \
  -v ./jdbc-drivers:/opt/jdbc-drivers \
  -e GOVPAY_DB_TYPE=postgresql \
  -e GOVPAY_DB_SERVER=postgres:5432 \
  -e GOVPAY_DB_NAME=govpay \
  -e GOVPAY_DB_USER=govpay \
  -e GOVPAY_DB_PASSWORD=<password> \
  linkitaly/govpay-fdr-batch
```

Per l'acquisizione da file system si monta la directory dei flussi e si impostano `GOVPAY_FDR_INPUT_ENABLED=true` e `GOVPAY_FDR_INPUT_DIR`; il processo gira con l'utente `govpay`, che deve poter scrivere nella directory.

Variabili d'ambiente dell'immagine, oltre alle proprietà Spring Boot in forma di variabile (es. `GOVPAY_BATCH_THREAD_POOL_SIZE=10`, `GOVPAY_FDR_INPUT_ENABLED=true`):

| Variabile | Obbligatoria | Default | Descrizione |
|-----------|--------------|---------|-------------|
| `GOVPAY_DB_TYPE` | sì | - | Tipo di database: `postgresql`, `mysql`, `mariadb`, `oracle` |
| `GOVPAY_DB_SERVER` | sì | - | Server del database, `host` o `host:porta` (porta di default del database se omessa) |
| `GOVPAY_DB_NAME` | sì | - | Nome del database (per Oracle, service name o SID) |
| `GOVPAY_DB_USER` | sì | - | Utente del database |
| `GOVPAY_DB_PASSWORD` | sì | - | Password del database (se manca l'avvio prosegue con un avviso) |
| `GOVPAY_DS_CONN_PARAM` | no | - | Parametri aggiunti all'URL JDBC (es. `zeroDateTimeBehavior=convertToNull` per MySQL) |
| `GOVPAY_DS_JDBC_LIBS` | no | `/opt/jdbc-drivers` | Directory dei driver JDBC |
| `GOVPAY_ORACLE_JDBC_URL_TYPE` | no | `servicename` | Formato dell'URL Oracle: `servicename` o `sid` |
| `ORACLE_TNS_ADMIN` | no | - | Directory dei file TNS di Oracle |
| `GOVPAY_FDR_MIN_POOL` | no | `2` | Connessioni minime inattive del pool |
| `GOVPAY_FDR_MAX_POOL` | no | `10` | Connessioni massime del pool |
| `GOVPAY_FDR_BATCH_USA_CRON` | no | - | `true`, `si`, `yes` o `1`: esecuzione singola con il profilo `cron`, senza server HTTP; il container termina a fine job e va pianificato dall'esterno (es. CronJob Kubernetes). Altrimenti scheduler interno |
| `GOVPAY_FDR_BATCH_INTERVALLO_CRON` | no | `120` | Minuti tra un'esecuzione e l'altra con lo scheduler interno, intero maggiore di zero; l'entrypoint la converte in `SCHEDULER_FDRACQUISITIONJOB_FIXEDDELAYSTRING`. Un valore non valido blocca l'avvio |
| `SCHEDULER_FDRACQUISITIONJOB_FIXEDDELAYSTRING` | no | - | Intervallo in millisecondi; se impostata ha la precedenza su `GOVPAY_FDR_BATCH_INTERVALLO_CRON` |
| `SERVER_PORT` | no | `10001` | Porta HTTP con lo scheduler interno |
| `MANAGEMENT_SERVER_PORT` | no | porta del servizio | Porta dedicata agli endpoint actuator |
| `MANAGEMENT_ENDPOINTS` | no | `health,info,metrics,prometheus` | Endpoint actuator esposti |
| `LOG_LEVEL_REFRESH_INTERVAL` | no | `60s` | Intervallo di rilettura dei livelli di log dal database |
| `GOVPAY_FDR_POP_DB_SKIP` | no | `TRUE` | `FALSE` crea all'avvio le tabelle Spring Batch, se manca la tabella `GOVPAY_FDR_DB_CHECK_TABLE`, e la tabella `fr_temp`, se manca |
| `GOVPAY_FDR_DB_CHECK_TABLE` | no | `batch_job_execution_context` | Tabella che indica che lo schema Spring Batch è già presente |
| `GOVPAY_FDR_LIVE_DB_CHECK_SKIP` | no | `FALSE` | Salta l'attesa della porta del database prima dell'inizializzazione |
| `GOVPAY_FDR_LIVE_DB_CHECK_MAX_RETRY` | no | `30` | Tentativi di connessione alla porta del database |
| `GOVPAY_FDR_LIVE_DB_CHECK_SLEEP_TIME` | no | `2` | Secondi tra un tentativo e l'altro |
| `GOVPAY_FDR_LIVE_DB_CHECK_CONNECT_TIMEOUT` | no | `5` | Timeout di ogni tentativo di connessione, in secondi |
| `GOVPAY_FDR_READY_DB_CHECK_SKIP` | no | `FALSE` | Salta la verifica delle tabelle e le crea comunque |
| `GOVPAY_FDR_READY_DB_CHECK_MAX_RETRY` | no | `5` | Tentativi della verifica delle tabelle |
| `GOVPAY_FDR_READY_DB_CHECK_SLEEP_TIME` | no | `2` | Secondi tra un tentativo e l'altro |
| `GOVPAY_FDR_JVM_MAX_RAM_PERCENTAGE` | no | `80` | `-XX:MaxRAMPercentage` della JVM |
| `GOVPAY_FDR_JVM_INITIAL_RAM_PERCENTAGE` | no | - | `-XX:InitialRAMPercentage` della JVM |
| `GOVPAY_FDR_JVM_MIN_RAM_PERCENTAGE` | no | - | `-XX:MinRAMPercentage` della JVM |
| `GOVPAY_FDR_JVM_MAX_METASPACE_SIZE` | no | - | `-XX:MaxMetaspaceSize` della JVM |
| `GOVPAY_FDR_JVM_MAX_DIRECT_MEMORY_SIZE` | no | - | `-XX:MaxDirectMemorySize` della JVM |
| `JAVA_OPTS` | no | - | Opzioni aggiuntive della JVM |

Il Dockerfile imposta anche, con i valori di default del batch, `SPRING_BATCH_JOB_ENABLED`, `GOVPAY_BATCH_ENABLED`, `GOVPAY_BATCH_MAX_RETRIES`, `GOVPAY_BATCH_PAGE_SIZE`, `GOVPAY_BATCH_THREAD_POOL_SIZE`, `GOVPAY_BATCH_HEADERS_CHUNK_SIZE`, `GOVPAY_BATCH_METADATA_CHUNK_SIZE`, `GOVPAY_BATCH_PAYMENTS_CHUNK_SIZE`, `GOVPAY_BATCH_SKIP_LIMIT`, `GOVPAY_BATCH_STALE_THRESHOLD_MINUTES`, `MANAGEMENT_ENDPOINTS_WEB_BASE_PATH`, i parametri del pool `SPRING_DATASOURCE_HIKARI_*` (vedi [Database](#database)) e `LOGGING_FILE_NAME`: si possono sovrascrivere con `-e`.

L'identificativo del nodo (`govpay.batch.cluster-id`) è impostato dall'entrypoint, con la variabile `GOVPAY_BATCH_CLUSTER_ID`, all'indirizzo IP del container o al suo hostname se l'IP non si ricava: un valore passato dall'esterno viene sovrascritto. Le altre variabili che compaiono negli script dell'immagine (es. `INTERVALLO_MINUTI`, `HSQLDB_SHA256`) sono interne e non vanno impostate. I messaggi dell'entrypoint sono nei log del container.

Costruzione dell'immagine in locale, scansione delle vulnerabilità e pacchetti inclusi sono descritti in [docker/DOCKER.md](docker/DOCKER.md) e [docker/SCANSIONE.md](docker/SCANSIONE.md).

## Monitoraggio

Con lo scheduler interno gli endpoint actuator rispondono di default sulla porta del servizio (`8080` con il jar, `10001` nel container; con il war, sotto il context path dell'applicazione):

```bash
curl http://localhost:10001/actuator/health       # stato, compresa la connessione al database
curl http://localhost:10001/actuator/prometheus   # metriche in formato Prometheus
```

Per spostarli su una porta dedicata impostare `management.server.port` (nel container `MANAGEMENT_SERVER_PORT`). L'endpoint `loggers`, che permette di cambiare i livelli di log a caldo, non è esposto di default perché il servizio non ha autenticazione: abilitarlo solo insieme a una porta dedicata e non raggiungibile dall'esterno, per esempio `MANAGEMENT_SERVER_PORT=9090` e `MANAGEMENT_ENDPOINTS=health,info,metrics,prometheus,loggers`. In alternativa i livelli si cambiano dalla console, tramite la tabella `configurazione`.

A fine esecuzione il batch scrive nel log un riepilogo con i flussi acquisiti, scartati e in errore per step. Con il profilo `cron` non c'è server HTTP: l'esito si ricava dall'exit code del processo e dai log.

## Avvio manuale via REST

Con lo scheduler interno il batch espone, sotto `/api/batch`, l'API comune ai batch GovPay (`AbstractBatchController` di `govpay-common`). Non ha autenticazione: va esposta solo sulla rete interna.

| Metodo e path | Descrizione |
|---------------|-------------|
| `GET /api/batch/run` | Avvia il job in modo asincrono (`202`, oppure `409` se è già in esecuzione) |
| `GET /api/batch/run?force=true` | Avvia il job anche se risulta un'esecuzione in corso |
| `GET /api/batch/status` | Stato corrente del job |
| `GET /api/batch/lastExecution` | Ultima esecuzione completata |
| `GET /api/batch/nextExecution` | Prossima esecuzione stimata |
| `GET /api/batch/info` | Nome e descrizione del batch |
| `GET /api/batch/executions` | Elenco paginato delle esecuzioni |
| `GET /api/batch/executions/{executionId}` | Dettaglio di un'esecuzione |
| `DELETE /api/batch/executions/{executionId}` | Richiede l'arresto di un'esecuzione |
| `GET /api/batch/cache/clear` | Svuota la cache dei connettori e dei client verso pagoPA |

```bash
curl http://localhost:10001/api/batch/run
curl http://localhost:10001/api/batch/status
```

## Elaborazione

### Acquisizione incrementale

Per ogni dominio la data di partenza (`publishedGt`) è la data di pubblicazione più recente tra i flussi del dominio già in `fr`; per un dominio senza flussi si chiede l'elenco completo. Se la data è più vecchia della finestra accettata da pagoPA (`govpay.batch.published-gt-max-age-days`) si applica `govpay.batch.published-gt-stale-strategy`.

Un flusso già presente in `fr` con la stessa revisione non viene riacquisito, né in `fr_temp` (step headers) né prima del salvataggio (step payments). Una nuova revisione dello stesso flusso viene salvata e le righe precedenti sono marcate obsolete.

### Riconciliazione e controlli

Ogni pagamento del flusso diventa una riga di `rendicontazioni`, collegata quando possibile al pagamento (`pagamenti`) e alla pendenza (`singoli_versamenti`) di GovPay. Lo stato è:

| Stato | Quando |
|-------|--------|
| `OK` | Nessuna anomalia |
| `ANOMALA` | Anomalie sul singolo pagamento (es. importo diverso, pagamento non trovato, rendicontazione duplicata nel flusso) |
| `ALTRO_INTERMEDIARIO` | Il pagamento non è in GovPay e lo IUV non è tra quelli generati da GovPay per il dominio: la posizione è gestita da un altro intermediario |

Il flusso è `ACCETTATA` oppure `ANOMALA` se la somma degli importi o il numero dei pagamenti non corrispondono alla testata o se una rendicontazione riferisce più pagamenti. Le anomalie sono salvate con il loro codice in `fr.descrizione_stato` e `rendicontazioni.anomalie`.

Le chiamate a pagoPA sono eseguite al massimo `govpay.batch.max-retries` volte per errori HTTP o di rete; un dominio che continua a fallire nello step headers viene saltato senza fermare gli altri.

### Acquisizione da file system

Canale alternativo alle API pagoPA, disabilitato di default: i flussi depositati come file JSON in `govpay.fdr.input.dir` vengono acquisiti all'inizio di ogni esecuzione del job, prima dell'interrogazione di pagoPA, con la stessa persistenza, riconciliazione e gestione delle anomalie.

È una **procedura di emergenza**, pensata per i flussi usciti dalla finestra di ricerca di pagoPA (30 giorni), per i tracciati forniti direttamente dal PSP o dall'Ente e per sanare disallineamenti senza interventi manuali sul database. Per questo la directory può non essere configurata, non esistere o restare vuota: in quei casi lo step non produce elementi, non crea directory e non fallisce.

**Formato del file**: la risposta della GET puntuale sul flusso (`/organizations/{organizationId}/fdrs/{fdr}/revisions/{revision}/psps/{pspId}`) con in più l'elenco dei pagamenti nel campo `payments`, cioè l'unione dei due endpoint pagoPA. Le date sono accettate sia come stringa ISO sia come secondi dall'epoch; i campi non riconosciuti vengono ignorati.

```json
{
  "fdr": "2026-08-06ABI03365-B6FXC00000004719",
  "fdrDate": 1786109246.000000000,
  "revision": 1,
  "published": 1786109996.592019000,
  "status": "PUBLISHED",
  "sender": { "type": "BIC_CODE", "pspId": "ABI03365", "pspName": "CHERRY BANK SPA" },
  "receiver": { "organizationId": "09788660968", "organizationName": "RIENERGIA SRLS" },
  "regulation": "Bonifico SEPA-03365-B6FXC",
  "regulationDate": "2026-08-06",
  "computedTotPayments": 1,
  "computedSumPayments": 70.43,
  "payments": [
    {
      "index": 1,
      "iuv": "00551000024916517",
      "iur": "8c1d4c6cf55a42108cc8820456031b0f",
      "pay": 70.43,
      "payDate": "2026-08-06T00:00:00Z",
      "payStatus": "EXECUTED",
      "idTransfer": 1
    }
  ]
}
```

Se `totPayments` e `sumPayments` sono assenti si usano `computedTotPayments` e `computedSumPayments`, così i controlli di quadratura restano attivi.

**Ciclo di vita dei file**: un file non viene mai cancellato.

| Esito | Destinazione |
|-------|--------------|
| Flusso acquisito | `processed-dir`, con il nome originale |
| Flusso già presente in `fr` | `processed-dir`, senza reinserimento |
| File malformato, dominio non censito o non abilitato, scrittura fallita | `error-dir`, con un `.error.txt` che contiene il motivo |

Le directory di archiviazione vengono create solo quando c'è un file da archiviare.

**Più nodi**: la directory può essere condivisa. Ogni nodo prende in carico un file rinominandolo in `<nome>.<cluster-id>.processing` con una move atomica; se la rinomina fallisce il file è di un altro nodo e viene ignorato. Un file rimasto con quel suffisso indica un nodo terminato durante l'elaborazione: l'operatore può rinominarlo per rimetterlo in coda.

**Tracciamento**: ogni file produce un evento GDE `ACQUISIZIONE_FLUSSO_FILE_SYSTEM` di categoria `INTERNO`, con esito `OK` per i flussi acquisiti e i duplicati e `KO` per gli scarti.

## Licenza

GovPay FDR Batch è distribuito con licenza [GNU General Public License v3.0](LICENSE).
