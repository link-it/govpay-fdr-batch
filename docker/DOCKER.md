# GovPay FDR - Configurazione Docker

Containerizzazione Docker per il batch GovPay FDR che gestisce l'acquisizione dei flussi di rendicontazione tramite il servizio REST di pagoPA.

## Panoramica

Questa configurazione Docker fornisce:
- Supporto multi-database (PostgreSQL, MySQL/MariaDB, Oracle)
- Modalità di deployment flessibili (schedulato da orchestratore o modalita cron)
- Integrazione con il servizio FDR di pagoPA
- Integrazione opzionale con GDE (Giornale degli Eventi)
- Health check e monitoraggio
- Connection pooling configurabile

## Avvio Rapido

### 1. Costruire l'Immagine Docker

```bash
# Build con supporto PostgreSQL (predefinito)
./build_image.sh
```

### 2. Configurazione obbligatoria
- `GOVPAY_DB_TYPE`: Tipo database (postgresql, mysql, mariadb, oracle)
- `GOVPAY_DB_SERVER`: Server database (formato: host:porta)
- `GOVPAY_DB_NAME`: Nome del database
- `GOVPAY_DB_USER`: Username del database
- `GOVPAY_DB_PASSWORD`: Password del database

**Nota:** La configurazione della connessione verso le API pagoPA FDR (URL, credenziali, timeouts) viene gestita tramite la tabella `CONNETTORI` del database GovPay, utilizzando la libreria `govpay-common`.

### 3. Avviare i Servizi

```bash
# Avvio con docker-compose
docker compose up -d

# Visualizzare i log
docker compose logs -f govpay-fdr

# Verificare lo stato
docker compose ps
```

## Architettura

```
┌─────────────────────────────────────┐
│   GovPay FDR Batch Processor        │
│                                     │
│  ┌──────────────────────────────┐  │
│  │   Applicazione Spring Boot   │  │
│  │   - Spring Batch             │  │
│  │   - Client FDR               │  │
│  │   - Client GDE (opzionale)   │  │
│  └──────────────────────────────┘  │
│              │                      │
│              ▼                      │
│  ┌──────────────────────────────┐  │
│  │   HikariCP Connection Pool   │  │
│  └──────────────────────────────┘  │
└─────────────────┬───────────────────┘
                  │
                  ▼
      ┌───────────────────────┐
      │   Database (RDBMS)    │
      │  - PostgreSQL         │
      │  - MySQL/MariaDB      │
      │  - Oracle             │
      └───────────────────────┘
```

## Modalità di Deployment

### Modalità CRON (Auto-schedulata)

Esecuzione continua con job batch auto-schedulati tramite Spring Scheduler:

```env
GOVPAY_FDR_BATCH_USA_CRON=true
GOVPAY_FDR_BATCH_INTERVALLO_CRON=5  # Intervallo in minuti (default: 5)
SERVER_PORT=10001  # Porta per Actuator (default: 10001)
```

**Caratteristiche:**
- Il container rimane attivo come daemon
- Scheduler interno esegue il batch ad intervalli regolari
- Espone endpoint Spring Boot Actuator per health check e metriche
- Ideale per deployment con orchestratori (Kubernetes, Docker Swarm, systemd)

### Modalità GESTITO (Schedulazione Esterna)

Esegue il batch una volta ed esce (per schedulazione esterna tramite cron, Kubernetes CronJob, ecc.):

```env
GOVPAY_FDR_BATCH_USA_CRON=false  # o non impostare la variabile
```

**Caratteristiche:**
- Il container esegue il batch ed esce immediatamente
- Non espone endpoint Actuator
- La schedulazione è gestita esternamente (cron, orchestratore)
- Ideale per CronJob di Kubernetes o cron di sistema

Esempio di configurazione cron:
```bash
# Esecuzione ogni ora
0 * * * * docker run --rm --env-file .env govpay-fdr-batch:latest
```

Esempio CronJob Kubernetes:
```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: govpay-fdr-batch
spec:
  schedule: "0 * * * *"  # Ogni ora
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: govpay-fdr
            image: govpay-fdr-batch:latest
            envFrom:
            - configMapRef:
                name: govpay-fdr-config
          restartPolicy: OnFailure
```

## Configurazione Database

Il container supporta la nomenclatura delle variabili compatibile con govpay-docker. L'URL JDBC viene costruito automaticamente in base al tipo di database.

### PostgreSQL (Default)

```env
GOVPAY_DB_TYPE=postgresql
GOVPAY_DB_SERVER=postgres:5432
GOVPAY_DB_NAME=govpay
GOVPAY_DB_USER=govpay
GOVPAY_DB_PASSWORD=yourpassword
```

**Parametri opzionali:**
```env
GOVPAY_DS_CONN_PARAM=sslmode=require&connectTimeout=10  # Parametri JDBC aggiuntivi
GOVPAY_FDR_MIN_POOL=2  # Connessioni minime nel pool (default: 2)
GOVPAY_FDR_MAX_POOL=10  # Connessioni massime nel pool (default: 10)
```

### MySQL/MariaDB

```env
GOVPAY_DB_TYPE=mysql  # oppure mariadb
GOVPAY_DB_SERVER=mysql:3306
GOVPAY_DB_NAME=govpay
GOVPAY_DB_USER=govpay
GOVPAY_DB_PASSWORD=yourpassword
```

**Parametri opzionali:**
```env
GOVPAY_DS_CONN_PARAM=zeroDateTimeBehavior=convertToNull&useSSL=false
```

### Oracle

```env
GOVPAY_DB_TYPE=oracle
GOVPAY_DB_SERVER=oracle:1521
GOVPAY_DB_NAME=XE  # Service name o SID
GOVPAY_DB_USER=govpay
GOVPAY_DB_PASSWORD=yourpassword
```

**Configurazione Service Name (default):**
```env
GOVPAY_ORACLE_JDBC_URL_TYPE=servicename  # Genera: jdbc:oracle:thin:@//host:port/service
```

**Configurazione SID:**
```env
GOVPAY_ORACLE_JDBC_URL_TYPE=sid  # Genera: jdbc:oracle:thin:@host:port:sid
```

**Nota:** Il formato con TNS Names non è attualmente supportato dalla configurazione automatica. Per utilizzare TNS Names, è necessario costruire manualmente l'URL JDBC.

### Inizializzazione Database Automatica

Il container può inizializzare automaticamente le tabelle del batch se non esistono:

```env
GOVPAY_FDR_POP_DB_SKIP=FALSE  # Abilita inizializzazione (default: TRUE)
GOVPAY_FDR_DB_CHECK_TABLE=batch_job_execution_context  # Tabella di riferimento
```

**Configurazione health check:**
```env
# Liveness check (connessione TCP al DB)
GOVPAY_FDR_LIVE_DB_CHECK_SKIP=FALSE
GOVPAY_FDR_LIVE_DB_CHECK_MAX_RETRY=30
GOVPAY_FDR_LIVE_DB_CHECK_SLEEP_TIME=2
GOVPAY_FDR_LIVE_DB_CHECK_CONNECT_TIMEOUT=5

# Readiness check (verifica esistenza tabelle)
GOVPAY_FDR_READY_DB_CHECK_SKIP=FALSE
GOVPAY_FDR_READY_DB_CHECK_MAX_RETRY=5
GOVPAY_FDR_READY_DB_CHECK_SLEEP_TIME=2
```

## Integrazione con GDE (Giornale degli Eventi)

L'integrazione con GDE (Giornale degli Eventi) viene configurata tramite la tabella `CONFIGURAZIONE` del database GovPay.
Non sono necessarie variabili d'ambiente per abilitare o configurare il GDE.

## Certificati TLS / CA interne (OAuth2, endpoint HTTPS)

Quando un endpoint HTTPS usato dal batch (es. il token endpoint OAuth2 di Keycloak, o le API pagoPA dietro un reverse proxy) presenta un certificato firmato da una **CA interna** non presente nel truststore della JVM, si ottiene:

```
javax.net.ssl.SSLHandshakeException: PKIX path building failed:
  unable to find valid certification path to requested target
```

Non è un errore dell'applicazione: la JVM non riconosce la CA. Va aggiunta la CA al truststore. Di seguito la procedura **senza ricostruire l'immagine**, gestendo la CA tramite **ConfigMap** su Kubernetes/OpenShift.

L'idea: l'entrypoint rispetta la variabile `JAVA_OPTS`; un **initContainer** costruisce a runtime un truststore (copia del `cacerts` di default + la CA interna) in un volume condiviso, e il container principale lo usa via `-Djavax.net.ssl.trustStore`. In questo modo si mantengono le CA pubbliche (necessarie per pagoPA) e si aggiunge quella interna.

### 1. ConfigMap con la CA

```bash
# Recupera il PEM della CA (dal team IdM/OCP, oppure):
openssl s_client -connect <host-keycloak>:443 -showcerts </dev/null 2>/dev/null \
  | openssl x509 -outform PEM > ca-interna.crt

oc create configmap trusted-ca \
  --from-file=ca-interna.crt=ca-interna.crt \
  -n <namespace>
```

Usare la **CA emittente** (non il certificato del server). Per catene con più CA aggiungere più `--from-file`.

### 2. Deployment: initContainer + volumi + JAVA_OPTS

```yaml
spec:
  template:
    spec:
      volumes:
        - name: ca-pem                 # CA (read-only) dalla ConfigMap
          configMap:
            name: trusted-ca
        - name: truststore             # truststore costruito a runtime
          emptyDir: {}

      initContainers:
        - name: build-truststore
          image: <STESSA_IMMAGINE_DEL_BATCH>   # ha keytool e il cacerts di default
          command: ["/bin/sh","-c"]
          args:
            - |
              set -e
              cp "$JAVA_HOME/lib/security/cacerts" /truststore/cacerts   # mantiene le CA pubbliche
              chmod u+w /truststore/cacerts
              for f in /ca/*; do
                echo "Import $f"
                keytool -importcert -noprompt -trustcacerts \
                  -alias "$(basename "$f")" -file "$f" \
                  -keystore /truststore/cacerts -storepass changeit
              done
          volumeMounts:
            - { name: ca-pem,     mountPath: /ca,        readOnly: true }
            - { name: truststore, mountPath: /truststore }

      containers:
        - name: govpay-fdr-batch
          # ... image/ports invariati ...
          env:
            - name: JAVA_OPTS          # se già usato altrove, APPENDERE questi flag
              value: >-
                -Djavax.net.ssl.trustStore=/truststore/cacerts
                -Djavax.net.ssl.trustStorePassword=changeit
                -Djavax.net.ssl.trustStoreType=PKCS12
          volumeMounts:
            - { name: truststore, mountPath: /truststore, readOnly: true }
```

Note:
- `<STESSA_IMMAGINE_DEL_BATCH>`: usare la stessa immagine del batch garantisce lo stesso `JAVA_HOME` (`/opt/java/openjdk`) e lo stesso `cacerts`; `keytool` è incluso nel JRE e funziona anche da utente non-root.
- Il `cacerts` di temurin è in formato **PKCS12** con password `changeit` (da qui `trustStoreType=PKCS12`).
- In modalità **CRON come CronJob K8s**, replicare la stessa struttura sotto `spec.jobTemplate.spec.template.spec`.

### 3. Applica e verifica

```bash
oc apply -f deployment.yaml
oc rollout restart deploy/govpay-fdr-batch -n <namespace>
# nel log dell'initContainer: "Certificate was added to keystore"
oc logs deploy/govpay-fdr-batch -c build-truststore -n <namespace>
```

### Rotazione / aggiunta CA

Aggiornare la ConfigMap e riavviare il pod: l'initContainer ricostruisce il truststore. Nessuna modifica all'immagine.

### Alternativa OpenShift (CA già nel trust del cluster)

Se il certificato è firmato da una CA già nel bundle del cluster, creare una ConfigMap con la label `config.openshift.io/inject-trusted-cabundle: "true"` (OCP la popola con `ca-bundle.crt`) e usarla come sorgente `/ca` nell'initContainer: stesso meccanismo, ma la CA la fornisce OpenShift.

> ⚠️ Non puntare `-Djavax.net.ssl.trustStore` a un truststore contenente **solo** la CA interna: sostituirebbe il default e romperebbe il TLS verso pagoPA. Usare sempre una **copia** del `cacerts` con la CA interna aggiunta (come sopra).

## Configuration Reference

### Variabili d'Ambiente - Database

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GOVPAY_DB_TYPE` | **Yes** | - | Tipo database: `postgresql`, `mysql`, `mariadb`, `oracle` |
| `GOVPAY_DB_SERVER` | **Yes** | - | Server database nel formato `host:porta` |
| `GOVPAY_DB_NAME` | **Yes** | - | Nome del database o service name |
| `GOVPAY_DB_USER` | **Yes** | - | Username del database |
| `GOVPAY_DB_PASSWORD` | **Yes** | - | Password del database |
| `GOVPAY_DS_CONN_PARAM` | No | - | Parametri aggiuntivi URL JDBC (es: `sslmode=require`) |
| `GOVPAY_ORACLE_JDBC_URL_TYPE` | No | `servicename` | Oracle: `servicename` o `sid` |
| `GOVPAY_FDR_MIN_POOL` | No | `2` | Connessioni minime pool HikariCP |
| `GOVPAY_FDR_MAX_POOL` | No | `10` | Connessioni massime pool HikariCP |
| `GOVPAY_DS_JDBC_LIBS` | No | `/opt/jdbc-drivers` | Percorso driver JDBC |

### Variabili d'Ambiente - Modalità Deployment

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GOVPAY_FDR_BATCH_USA_CRON` | No | `false` | Modalità CRON: `true`, `si`, `yes`, `1` |
| `GOVPAY_FDR_BATCH_INTERVALLO_CRON` | No | `5` | Intervallo scheduler in minuti (modalità CRON) |
| `SERVER_PORT` | No | `10001` | Porta Actuator (modalità CRON) |

### Variabili d'Ambiente - Inizializzazione Database

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GOVPAY_FDR_POP_DB_SKIP` | No | `TRUE` | Salta inizializzazione DB: `TRUE`/`FALSE` |
| `GOVPAY_FDR_DB_CHECK_TABLE` | No | `batch_job_execution_context` | Tabella per readiness check |
| `GOVPAY_FDR_LIVE_DB_CHECK_SKIP` | No | `FALSE` | Salta liveness check (TCP) |
| `GOVPAY_FDR_LIVE_DB_CHECK_MAX_RETRY` | No | `30` | Tentativi max liveness check |
| `GOVPAY_FDR_LIVE_DB_CHECK_SLEEP_TIME` | No | `2` | Secondi tra tentativi liveness |
| `GOVPAY_FDR_LIVE_DB_CHECK_CONNECT_TIMEOUT` | No | `5` | Timeout connessione TCP (secondi) |
| `GOVPAY_FDR_READY_DB_CHECK_SKIP` | No | `FALSE` | Salta readiness check (tabelle) |
| `GOVPAY_FDR_READY_DB_CHECK_MAX_RETRY` | No | `5` | Tentativi max readiness check |
| `GOVPAY_FDR_READY_DB_CHECK_SLEEP_TIME` | No | `2` | Secondi tra tentativi readiness |

### Variabili d'Ambiente - JVM Memory

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GOVPAY_FDR_JVM_MAX_RAM_PERCENTAGE` | No | `80` | Percentuale RAM massima utilizzabile dalla JVM |
| `GOVPAY_FDR_JVM_INITIAL_RAM_PERCENTAGE` | No | - | Percentuale RAM iniziale |
| `GOVPAY_FDR_JVM_MIN_RAM_PERCENTAGE` | No | - | Percentuale RAM minima |
| `GOVPAY_FDR_JVM_MAX_METASPACE_SIZE` | No | - | Dimensione max Metaspace (es: `256m`) |
| `GOVPAY_FDR_JVM_MAX_DIRECT_MEMORY_SIZE` | No | - | Dimensione max Direct Memory (es: `128m`) |
| `JAVA_OPTS` | No | - | Opzioni aggiuntive JVM |


### Health Check

In modalità CRON, il container espone gli endpoint Spring Boot Actuator:

```bash
# Health check
curl http://localhost:10001/actuator/health

# Verifica dettagliata
curl http://localhost:10001/actuator/health/liveness
curl http://localhost:10001/actuator/health/readiness
```

**Nota:** La porta di default è `10001` (configurabile tramite `SERVER_PORT`)

### Logs

```bash
# Seguire i log del container
docker-compose logs -f govpay-fdr

# Log dall'interno del container
docker exec -it govpay-fdr-batch tail -f /var/log/govpay/govpay-fdr.log

# Debug dell'entrypoint (per troubleshooting avvio)
docker exec -it govpay-fdr-batch cat /tmp/entrypoint_debug.log
```

### Metrics

Accesso alle metriche Spring Boot (solo modalità CRON):
```bash
# Metriche generali
curl http://localhost:10001/actuator/metrics

# Metriche specifiche
curl http://localhost:10001/actuator/metrics/jvm.memory.used
curl http://localhost:10001/actuator/metrics/hikaricp.connections.active
```

## File Structure

```
govpay-fdr-batch/docker/
├── govpay-fdr
|   └── Dockerfile.github          # Definizione immagine Docker
├── build_image.sh                 # Script di build
├── DOCKER.md                      # Questa documentazione
└── commons/
    ├── entrypoint.sh             # Script di avvio container
    └── init_fdr_db.sh            # Script inizializzazione Database
```

## Esempi di Configurazione

### Esempio Minimo (PostgreSQL + UAT)

```env
# Database
GOVPAY_DB_TYPE=postgresql
GOVPAY_DB_SERVER=postgres:5432
GOVPAY_DB_NAME=govpay
GOVPAY_DB_USER=govpay
GOVPAY_DB_PASSWORD=secret123

# Modalità auto-schedulata (ogni 5 minuti)
GOVPAY_FDR_BATCH_USA_CRON=true
```

**Nota:** La connessione verso le API pagoPA FDR viene configurata nella tabella `CONNETTORI` del database GovPay.

### Esempio Completo (Oracle + Produzione + Inizializzazione DB)

```env
# Database Oracle
GOVPAY_DB_TYPE=oracle
GOVPAY_DB_SERVER=oracle-prod.example.com:1521
GOVPAY_DB_NAME=GOVPAYDB
GOVPAY_DB_USER=govpay_fdr
GOVPAY_DB_PASSWORD=SecurePassword123!
GOVPAY_ORACLE_JDBC_URL_TYPE=servicename
GOVPAY_DS_CONN_PARAM=oracle.net.ssl_version=1.2

# Pool connessioni
GOVPAY_FDR_MIN_POOL=5
GOVPAY_FDR_MAX_POOL=20

# Inizializzazione database
GOVPAY_FDR_POP_DB_SKIP=FALSE
GOVPAY_FDR_LIVE_DB_CHECK_MAX_RETRY=60

# Modalità gestita esternamente (CronJob K8s)
GOVPAY_FDR_BATCH_USA_CRON=false

# JVM Memory
GOVPAY_FDR_JVM_MAX_RAM_PERCENTAGE=75
GOVPAY_FDR_JVM_MAX_METASPACE_SIZE=256m
```

## Supporto

Per problemi relativi a:
- **GovPay**: https://github.com/link-it/govpay
- **pagoPA GPD**: https://docs.pagopa.it/
- **Sviluppo Java**: Consultare `README.md`
