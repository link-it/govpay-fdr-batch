# GovPay FDR Batch - Immagine Docker

Immagine del batch che acquisisce da pagoPA i Flussi di Rendicontazione (FDR) degli Enti Creditori e li riconcilia con i pagamenti di GovPay. Cosa fa il batch, come si configura e l'elenco completo delle variabili d'ambiente sono nel [README](../README.md#esecuzione-con-docker): qui solo ciò che riguarda l'immagine.

## Contenuto

| Percorso | Contenuto |
|----------|-----------|
| `/opt/govpay-fdr/govpay-fdr-batch.jar` | Fat jar del batch |
| `/opt/jdbc-drivers` | Driver JDBC, da montare: non sono inclusi nell'immagine (vedi [jdbc-drivers/README.md](jdbc-drivers/README.md)) |
| `/opt/sql` | Script SQL di `sql.zip`: schema Spring Batch per database (`spring-batch/`), tabella `fr_temp` e indici per database (`postgresql/`, `mysql/`, `oracle/`, `sqlserver/`, `hsqldb/`), svecchiamento dei metadati (`cleanup/`) |
| `/opt/hsqldb-<versione>` | SqlTool di HSQLDB, usato per creare le tabelle all'avvio |
| `/var/log/govpay-fdr/govpay-fdr.log` | File di log dell'applicazione |

Il processo gira con l'utente `govpay`; l'entrypoint è `/usr/local/bin/entrypoint.sh` e scrive i propri messaggi sullo standard output del container.

## Costruzione

```bash
cd docker
./build_image.sh                       # ultima release pubblicata su GitHub
./build_image.sh -v <versione>         # una release specifica
./build_image.sh -l ../target/govpay-fdr-batch.jar -t govpay-fdr-batch:locale   # jar compilato in locale
```

Con una release, jar e `sql.zip` sono scaricati da GitHub e verificati con lo sha256 letto dalle API delle release (`-s` e `-q` per indicarlo a mano). Con `-l` si usa `Dockerfile.daFile` e gli script SQL vengono da `../target/sql.zip`, prodotto da `mvn clean install -Pjar,dist`: senza, l'immagine non ha gli script per creare le tabelle. `./build_image.sh -h` elenca tutte le opzioni.

## Modalità di esecuzione

| Modalità | Come si attiva | Comportamento |
|----------|----------------|---------------|
| Scheduler interno | default (`GOVPAY_FDR_BATCH_USA_CRON` non impostata o falsa) | Il container resta attivo ed esegue il job all'avvio e poi a intervalli; espone API REST e actuator sulla porta `10001` |
| Esecuzione singola | `GOVPAY_FDR_BATCH_USA_CRON=true` (anche `si`, `yes`, `1`) | Profilo `cron`: esegue il job una volta e termina, senza server HTTP. La pianificazione è esterna (cron di sistema, CronJob Kubernetes) |

Con lo scheduler interno l'intervallo si indica in minuti con `GOVPAY_FDR_BATCH_INTERVALLO_CRON` (default `120`), che l'entrypoint converte nella proprietà `scheduler.fdrAcquisitionJob.fixedDelayString`. Se è impostata direttamente `SCHEDULER_FDRACQUISITIONJOB_FIXEDDELAYSTRING` (in millisecondi), vale quella e `GOVPAY_FDR_BATCH_INTERVALLO_CRON` viene ignorata. Il valore usato è scritto nel log di avvio:

```
[INFO] ... - Porta HTTP: 10001, Intervallo scheduler: 120 minuti (7200000ms)
```

In esecuzione singola l'exit code è `0` anche quando il job fallisce: l'esito va letto nei log.

Esempio di CronJob Kubernetes per l'esecuzione singola:

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: govpay-fdr-batch
spec:
  schedule: "0 * * * *"
  concurrencyPolicy: Forbid
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: govpay-fdr
            image: linkitaly/govpay-fdr-batch
            env:
            - name: GOVPAY_FDR_BATCH_USA_CRON
              value: "true"
            envFrom:
            - configMapRef:
                name: govpay-fdr-config
            - secretRef:
                name: govpay-fdr-db
            volumeMounts:
            - { name: jdbc-drivers, mountPath: /opt/jdbc-drivers, readOnly: true }
          volumes:
          - name: jdbc-drivers
            persistentVolumeClaim:
              claimName: govpay-jdbc-drivers
          restartPolicy: OnFailure
```

## Inizializzazione del database

Con `GOVPAY_FDR_POP_DB_SKIP=FALSE` l'entrypoint attende la porta del database e controlla separatamente due tabelle:

- `GOVPAY_FDR_DB_CHECK_TABLE` (default `batch_job_execution_context`): se manca, crea le tabelle dei metadati Spring Batch con `/opt/sql/spring-batch/schema-<database>.sql`;
- `fr_temp`: se manca, la crea con `/opt/sql/<database>/create.sql`.

Si esegue solo lo script di ciò che manca, così il batch si può aggiungere a un database dove un altro batch GovPay ha già creato le tabelle Spring Batch. Con `GOVPAY_FDR_READY_DB_CHECK_SKIP=TRUE` i controlli si saltano e si eseguono entrambi gli script. Le tabelle di GovPay non vengono create: sono degli script di installazione di GovPay. Gli indici di `sql/<database>/add-indexes.sql` si applicano a mano.

L'inizializzazione supporta PostgreSQL, MySQL/MariaDB e Oracle; per SQL Server le tabelle si creano a mano con gli script di `sql.zip`.

## Giornale degli Eventi

L'invio degli eventi al GDE non si configura con variabili del container ma con il connettore `govpay_gde_api` di GovPay (tabella `connettori`).

## Acquisizione da file system

Per acquisire flussi depositati come file si monta una directory nel container e si impostano `GOVPAY_FDR_INPUT_ENABLED=true` e `GOVPAY_FDR_INPUT_DIR=<percorso nel container>`. L'utente `govpay` del container deve poter leggere, rinominare e spostare i file. Formato e ciclo di vita dei file sono descritti nel [README](../README.md#acquisizione-da-file-system).

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

## Scansione delle vulnerabilità

Le immagini pubblicate dalla pipeline sono scansionate prima della pubblicazione: vedi [SCANSIONE.md](SCANSIONE.md).
