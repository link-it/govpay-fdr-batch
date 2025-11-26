# GovPay FDR Batch - Implementazione Issue #1

## Sommario

Questo documento descrive l'implementazione completa del batch di acquisizione FDR REST come richiesto nell'issue #1.

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

### Step 3: Acquisizione Dettagli Pagamenti
- **Reader**: `FdrPaymentsReader` - Legge i record da `FR_TEMP` ordinati per data pubblicazione
- **Processor**: `FdrPaymentsProcessor` - Per ogni FDR:
  1. Chiama `/organizations/{organizationId}/fdrs/{fdr}/revisions/{revision}/psps/{pspId}` per ottenere i dettagli
  2. Chiama `/organizations/{organizationId}/fdrs/{fdr}/revisions/{revision}/psps/{pspId}/payments` per ottenere i pagamenti
  - Gestisce retry (3 tentativi) in caso di errore
- **Writer**: `FdrPaymentsWriter` - Salva i dati in:
  - Tabella `FR` (header del flusso)
  - Tabella `RENDICONTAZIONI` (singoli pagamenti)
  - Tenta di linkare i pagamenti esistenti nella tabella `PAGAMENTI`
  - Marca il record in `FR_TEMP` come processato

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
  - Lista flussi pubblicati
  - Lista pagamenti di un flusso
- Itera tutte le pagine fino al completamento

### ✅ Retry e Fault Tolerance
- 3 tentativi automatici per chiamate API fallite
- Skip di record problematici fino a un limite configurabile
- Log dettagliati per troubleshooting

### ✅ Multi-threading (Step 2)
- Elaborazione parallela dei domini
- Numero thread configurabile
- Throttling per evitare sovraccarico API

### ✅ Query Incrementali
- Ogni dominio traccia l'ultima data di acquisizione
- Parametro `publishedGt` usato per ottenere solo nuovi flussi
- Evita ri-acquisizione di dati già processati

### ✅ Deduplicazione
- Controllo esistenza in `FR_TEMP` prima dell'inserimento
- Controllo esistenza in `FR` prima del salvataggio finale
- Vincoli unique sul database

### ✅ Consistency Checks
- Tentativo di linking con tabelle `PAGAMENTI` e `SINGOLI_VERSAMENTI`
- Tracciamento stato elaborazione in `FR_TEMP`
- Transazioni per garantire consistenza

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

## Prossimi Passi

1. **Test Integrazione**: Configurare un ambiente di test con credenziali pagoPA valide
2. **Monitoring**: Aggiungere metriche (Spring Actuator) per monitorare le esecuzioni
3. **Notifiche**: Implementare notifiche in caso di errori critici
4. **Dashboard**: Creare endpoint REST per visualizzare lo stato delle esecuzioni
5. **Performance Tuning**: Ottimizzare parametri di threading e chunk size in base al carico

## Note Tecniche

- **Java Version**: 21 (richiesto da Spring Boot 3.5.6)
- **Spring Boot**: 3.5.6
- **Spring Batch**: Incluso in Spring Boot Starter
- **OpenAPI Generator**: 7.10.0
- **Database**: H2 (dev) / PostgreSQL (prod)
- **Build Tool**: Maven 3.6.3+
