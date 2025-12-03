<p align="center">
<img src="https://www.link.it/wp-content/uploads/2025/01/logo-govpay.svg" alt="GovPay Logo" width="200"/>
</p>

# GovPay - Porta di accesso al sistema pagoPA - FDR Batch

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=link-it_govpay-fdr-batch&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=link-it_govpay-fdr-batch)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://raw.githubusercontent.com/link-it/govpay-fdr-batch/main/LICENSE)

## Sommario

Batch Spring Boot per l'acquisizione automatica dei Flussi di Rendicontazione (FDR) da pagoPA tramite API REST.
Il sistema scarica, processa e riconcilia i flussi di rendicontazione con i pagamenti esistenti nel database GovPay.

## Architettura Implementata

### Step 1: Cleanup Tabella Temporanea
- **Classe**: `CleanupFrTempTasklet`
- **Funzione**: Svuota la tabella `FR_TEMP` prima di iniziare il processo di acquisizione

### Step 2: Acquisizione Headers FDR (Multi-threaded)
- **Reader**: `FdrHeadersReader` - Legge tutti i domini abilitati dal database
- **Processor**: `FdrHeadersProcessor` - Per ogni dominio, chiama l'API pagoPA per ottenere la lista dei flussi pubblicati
  - URL: `/organizations/{organizationId}/fdrs?publishedGt={ultima_data_acquisizione}`
  - Supporta paginazione automatica
  - Gestisce retry (3 tentativi) in caso di errore
- **Writer**: `FdrHeadersWriter` - Salva gli headers in `FR_TEMP` e aggiorna la data ultima acquisizione del dominio
- **Parallelizzazione**: Configurabile tramite `govpay.batch.thread-pool-size` (default: 5 thread)

### Step 3: Acquisizione Metadata FDR (PARTIZIONATO per dominio)
- **Partitioner**: `DominioPartitioner` - Crea una partizione per ogni dominio presente in `FR_TEMP`
- **Reader**: `FdrMetadataReader` - Legge i flussi di un singolo dominio da `FR_TEMP` ordinati per data pubblicazione
- **Processor**: `FdrMetadataProcessor` - Per ogni FDR:
  - Chiama `/organizations/{organizationId}/fdrs/{fdr}/revisions/{revision}/psps/{pspId}` per ottenere i metadati
  - Gestisce retry (3 tentativi) in caso di errore
- **Writer**: `FdrMetadataWriter` - Aggiorna i record in `FR_TEMP` con i metadati scaricati
- **Parallelizzazione**: Ogni dominio viene processato in una partizione separata in parallelo

### Step 4: Acquisizione Pagamenti (PARTIZIONATO per dominio)
- **Partitioner**: `DominioPartitioner` - Crea una partizione per ogni dominio presente in `FR_TEMP`
- **Reader**: `FdrPaymentsReader` - Legge i flussi di un singolo dominio da `FR_TEMP`
- **Processor**: `FdrPaymentsProcessor` - Per ogni FDR:
  - Chiama `/organizations/{organizationId}/fdrs/{fdr}/revisions/{revision}/psps/{pspId}/payments` per ottenere i pagamenti
  - Gestisce retry (3 tentativi) in caso di errore
- **Writer**: `FdrPaymentsWriter` - Salva i dati in:
  - Tabella `FR` (header del flusso)
  - Tabella `RENDICONTAZIONI` (singoli pagamenti)
  - Riconcilia con pagamenti esistenti nella tabella `PAGAMENTI`
  - Esegue verifiche semantiche e gestione anomalie
  - Marca il record in `FR_TEMP` come processato (delete)
- **Parallelizzazione**: Ogni dominio viene processato in una partizione separata in parallelo

## Entità Database

### DOMINI
- Memorizza i domini (enti creditori) abilitati
- Traccia l'ultima data di acquisizione per query incrementali

### FR_TEMP
- Tabella temporanea per gli headers FDR
- Pulita ad ogni esecuzione
- Usata come staging area prima dell'acquisizione completa

### FR
- Memorizza i flussi di rendicontazione completi
- Relazione con DOMINI (many-to-one)
- Vincolo unique su (cod_flusso, cod_psp, revision)

### RENDICONTAZIONI
- Memorizza i singoli pagamenti di ogni FDR
- Relazione con FR (many-to-one)
- Relazione opzionale con PAGAMENTI (many-to-one) per matching

### PAGAMENTI
- Tabella pre-esistente per i pagamenti
- Relazione opzionale con SINGOLI_VERSAMENTI

### SINGOLI_VERSAMENTI
- Tabella pre-esistente per le posizioni debitorie

## Script Database

Il progetto include script SQL per tutti i DBMS supportati da GovPay:

### Script disponibili (per ogni DBMS)
```
src/main/resources/sql/{dbms}/
├── create.sql           # Creazione tabelle (FR_TEMP e indici)
├── drop.sql            # Drop tabelle
├── delete.sql          # Pulizia dati
├── add-indexes.sql     # Aggiunta indici su tabelle esistenti
└── spring-batch/       # Script per tabelle Spring Batch
    ├── schema-{dbms}.sql
    └── drop-{dbms}.sql
```

### DBMS supportati
- `postgresql` - PostgreSQL 9.6+
- `mysql` - MySQL 5.7+ / MariaDB 10.3+
- `oracle` - Oracle 11g+
- `sqlserver` - SQL Server 2016+
- `hsqldb` - HSQLDB/H2 (per sviluppo e test)

## Configurazione

### Parametri API pagoPA
```properties
# URL base API pagoPA
pagopa.fdr.base-url=https://api.platform.pagopa.it/fdr-org/service/v1

# Chiave di sottoscrizione (obbligatoria)
pagopa.fdr.subscription-key=${PAGOPA_SUBSCRIPTION_KEY}

# Header per la chiave di sottoscrizione
pagopa.fdr.subscription-key-header=Ocp-Apim-Subscription-Key

# Timeout connessione (ms)
pagopa.fdr.connection-timeout=10000

# Timeout lettura (ms)
pagopa.fdr.read-timeout=30000

# Numero massimo di retry per chiamate fallite
pagopa.fdr.max-retries=3

# Dimensione pagina per richieste paginate
pagopa.fdr.page-size=1000
```

### Parametri Batch
```properties
# Abilitazione scheduling automatico
govpay.batch.enabled=true

# Espressione cron per schedulazione (default: ogni giorno alle 02:00)
govpay.batch.cron=0 0 2 * * ?

# Numero di thread per Step 2 (elaborazione parallela domini)
govpay.batch.thread-pool-size=5

# Dimensione chunk per elaborazione batch
govpay.batch.chunk-size=100

# Numero massimo di errori tollerati prima di fermare il job
govpay.batch.skip-limit=10
```

### Database
```properties
# Per sviluppo: H2 in-memory
spring.datasource.url=jdbc:h2:mem:fdrdb
spring.datasource.driver-class-name=org.h2.Driver

# Per produzione: PostgreSQL
#spring.datasource.url=jdbc:postgresql://localhost:5432/fdrdb
#spring.datasource.driver-class-name=org.postgresql.Driver
#spring.datasource.username=fdr_user
#spring.datasource.password=your_password
```

## Compilazione ed Esecuzione

### Compilazione
```bash
# Con Java 21 impostato come JAVA_HOME
export JAVA_HOME=/path/to/jdk-21
mvn clean install
```

### Esecuzione
```bash
# Avvio applicazione
java -jar target/govpay-fdr-batch-1.0.0-SNAPSHOT.jar

# Con variabili d'ambiente
export PAGOPA_SUBSCRIPTION_KEY=your-subscription-key
java -jar target/govpay-fdr-batch-1.0.0-SNAPSHOT.jar
```

### Trigger Manuale
Il job può essere eseguito manualmente invocando il metodo `FdrBatchScheduler.triggerManually()` tramite un endpoint REST o JMX.

## Caratteristiche Implementate

### ✅ Autenticazione API
- Header `Ocp-Apim-Subscription-Key` aggiunto automaticamente a tutte le richieste
- Configurabile tramite properties

### ✅ Paginazione
- Gestione automatica della paginazione per:
  - Lista flussi pubblicati (Step 2)
  - Lista pagamenti di un flusso (Step 4)
- Itera tutte le pagine fino al completamento

### ✅ Retry e Fault Tolerance
- 3 tentativi automatici per chiamate API fallite con backoff esponenziale (2s, 4s, 8s)
- Skip di record problematici fino a un limite configurabile
- Log dettagliati per troubleshooting
- Circuit breaker per protezione API pagoPA

### ✅ Elaborazione Parallela
- **Step 2 (Headers)**: Multi-threading configurabile per domini
- **Step 3 (Metadata)**: Partizionamento per dominio
- **Step 4 (Payments)**: Partizionamento per dominio
- Isolamento errori per dominio

### ✅ Query Incrementali
- Ogni dominio traccia l'ultima data di acquisizione
- Parametro `publishedGt` usato per ottenere solo nuovi flussi
- Evita ri-acquisizione di dati già processati

### ✅ Deduplicazione
- Controllo esistenza in `FR_TEMP` prima dell'inserimento (Step 2)
- Controllo esistenza in `FR` prima del download metadata (Step 3)
- Controllo esistenza in `FR` prima del salvataggio finale (Step 4)
- Vincoli unique sul database

### ✅ Riconciliazione e Verifiche
- Riconciliazione automatica con tabelle `PAGAMENTI` e `SINGOLI_VERSAMENTI`
- Verifiche semantiche (importi, numero pagamenti, ecc.)
- Gestione anomalie con classificazione
- Stati FR: ACCETTATA, ANOMALA
- Stati Rendicontazione: OK, ANOMALA, ALTRO_INTERMEDIARIO

### ✅ Multi-nodo e Recovery
- Gestione esecuzioni su cluster multi-nodo
- Lock distribuito su tabelle Spring Batch
- Recovery automatico job bloccati (timeout: 24h configurabile)
- Prevenzione esecuzioni concorrenti

### ✅ Integrazione GDE
- Tracciamento eventi nel Giornale Degli Eventi (GDE)
- Payload completo delle richieste/risposte API
- Tracciabilità completa del flusso di elaborazione

### ✅ Ottimizzazione Database
- Indici compositi per query critiche
- Script di creazione/aggiornamento per tutti i DBMS
- Update statistics per ottimizzatore query

## Logging

Il livello di log può essere configurato in `application.properties`:

```properties
logging.level.it.govpay.fdr.batch=DEBUG
logging.level.org.springframework.batch=INFO
logging.level.org.springframework.web.client=DEBUG
```

## Test

Il contesto Spring Boot si carica correttamente con:
- Database H2 in-memory
- Tutte le configurazioni batch
- Client API generato da OpenAPI

```bash
mvn test
```

## Roadmap

Per la lista completa delle migliorie previste, vedere la sezione [Lista Migliorie](#) nella documentazione del progetto.

### Alta Priorità
1. **Monitoring & Metriche**: Spring Boot Actuator + metriche custom (FDR processati, tempi, errori)
2. **Notifiche**: Sistema alerting per errori critici (email/Slack)
3. **Dashboard**: Endpoint REST per visualizzare stato job e anomalie
4. **Performance Tuning**: Ottimizzazione parametri in base al carico reale

### Media Priorità
5. **Circuit Breaker**: Resilience4j per protezione avanzata API pagoPA
6. **Containerizzazione**: Docker + Kubernetes manifests
7. **CI/CD Enhancement**: Code quality checks automatici, security scanning
8. **Documentazione Operativa**: Runbook troubleshooting, deployment guide

### Bassa Priorità
9. **Analytics & BI**: Report statistiche per business
10. **Batch Management UI**: Web UI per gestione job e anomalie

## Note Tecniche

- **Java Version**: 21 (richiesto da Spring Boot 3.5.6)
- **Spring Boot**: 3.5.6
- **Spring Batch**: 5.2.4 (incluso in Spring Boot Starter)
- **OpenAPI Generator**: 7.10.0
- **Database**: PostgreSQL (prod), MySQL, Oracle, SQL Server, H2/HSQLDB (dev)
- **Build Tool**: Maven 3.6.3+

## Documentazione

- **[ChangeLog](ChangeLog)** - Storia completa delle modifiche e release

## Contribuire

Per contribuire al progetto:
1. Fork del repository
2. Creare un branch per la feature (`git checkout -b feature/AmazingFeature`)
3. Commit delle modifiche (`git commit -m 'Add some AmazingFeature'`)
4. Push del branch (`git push origin feature/AmazingFeature`)
5. Aprire una Pull Request

Assicurarsi di:
- Seguire lo stile di codifica del progetto
- Aggiungere test per nuove funzionalità
- Aggiornare il ChangeLog seguendo il formato esistente
- Documentare le modifiche nel README se necessario

## License

Questo progetto è distribuito sotto licenza GPL v3. Vedere il file [LICENSE](LICENSE) per i dettagli.

## Contatti

- **Progetto**: [GovPay FDR Batch](https://github.com/link-it/govpay-fdr-batch)
- **Organizzazione**: [Link.it](https://www.link.it)

## Riconoscimenti

Questo progetto è parte dell'ecosistema [GovPay](https://www.govpay.it) per la gestione dei pagamenti della Pubblica Amministrazione italiana tramite pagoPA.
