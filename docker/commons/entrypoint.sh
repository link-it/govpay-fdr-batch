#!/bin/bash

##############################################################################
# GovPay FDR (Flussi di Rendicontazione) - Script di Entrypoint
#
# Supporta nomenclatura variabili legacy e govpay-docker
##############################################################################

set -e

# Debug di esecuzione (come govpay-docker)
exec 6<> /tmp/entrypoint_debug.log
exec 2>&6
set -x

# Funzioni di logging
log_info() { echo -e "\033[0;32m[INFO]\033[0m $(date '+%Y-%m-%d %H:%M:%S') - $1"; }
log_warn() { echo -e "\033[1;33m[WARN]\033[0m $(date '+%Y-%m-%d %H:%M:%S') - $1"; }
log_error() { echo -e "\033[0;31m[ERROR]\033[0m $(date '+%Y-%m-%d %H:%M:%S') - $1"; }

log_info "========================================"
log_info "Avvio GovPay FDR Batch Processor"
log_info "========================================"

##############################################################################
# Supporto variabili stile govpay-docker
##############################################################################



#
# Sanity check variabili minime attese
#
if [ -n "${GOVPAY_DB_TYPE}" -a -n "${GOVPAY_DB_SERVER}" -a -n  "${GOVPAY_DB_USER}" -a -n "${GOVPAY_DB_NAME}" ]
then
        [ -n "${GOVPAY_DB_PASSWORD}" ] || log_warn "La variabile GOVPAY_DB_PASSWORD non è stata impostata."
        log_info "Sanity check variabili obbligatorie ... ok."
else
    log_error "Sanity check variabili obbligatorie ... fallito."
    log_error "Devono essere settate almeno le seguenti variabili obbligatorie:
GOVPAY_DB_TYPE: ${GOVPAY_DB_TYPE}
GOVPAY_DB_SERVER: ${GOVPAY_DB_SERVER}
GOVPAY_DB_NAME: ${GOVPAY_DB_NAME}
GOVPAY_DB_USER: ${GOVPAY_DB_USER}
"
    exit 1
fi


# Imposta valori di default
GOVPAY_DS_JDBC_LIBS=${GOVPAY_DS_JDBC_LIBS:-/opt/jdbc-drivers}
GOVPAY_FDR_MIN_POOL=${GOVPAY_FDR_MIN_POOL:-2}
GOVPAY_FDR_MAX_POOL=${GOVPAY_FDR_MAX_POOL:-10}




    # Estrazione host e porta
    IFS=':' read -r DB_HOST DB_PORT <<< "${GOVPAY_DB_SERVER}"
    [ -z "${DB_PORT}" ] && case "${GOVPAY_DB_TYPE}" in
        postgresql) DB_PORT=5432 ;;
        mysql|mariadb) DB_PORT=3306 ;;
        oracle) DB_PORT=1521 ;;
    esac

    # Costruzione URL JDBC
    case "${GOVPAY_DB_TYPE}" in
        postgresql)
            SPRING_DATASOURCE_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${GOVPAY_DB_NAME}"
            GOVPAY_DS_DRIVER_CLASS="org.postgresql.Driver"
            GOVPAY_HYBERNATE_DIALECT="org.hibernate.dialect.PostgreSQLDialect"
            ;;
        mysql|mariadb)
            SPRING_DATASOURCE_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${GOVPAY_DB_NAME}"
            GOVPAY_DS_DRIVER_CLASS="com.mysql.cj.jdbc.Driver"
            GOVPAY_HYBERNATE_DIALECT="org.hibernate.dialect.MySQLDialect"
            ;;
        oracle)
            if [ "${GOVPAY_ORACLE_JDBC_URL_TYPE:-servicename}" == "servicename" ]; then
                SPRING_DATASOURCE_URL="jdbc:oracle:thin:@//${DB_HOST}:${DB_PORT}/${GOVPAY_DB_NAME}"
            else
                SPRING_DATASOURCE_URL="jdbc:oracle:thin:@${DB_HOST}:${DB_PORT}:${GOVPAY_DB_NAME}"
            fi
            GOVPAY_DS_DRIVER_CLASS="oracle.jdbc.OracleDriver"
            GOVPAY_HYBERNATE_DIALECT="org.hibernate.dialect.OracleDialect"
            [ -n "${ORACLE_TNS_ADMIN}" ] && JAVA_OPTS="${JAVA_OPTS} -Doracle.net.tns_admin=${ORACLE_TNS_ADMIN}"
            ;;
        *)
            log_error "GOVPAY_DB_TYPE non supportato: ${GOVPAY_DB_TYPE}"
            exit 1
            ;;
    esac

    # Aggiunta parametri di connessione
    [ -n "${GOVPAY_DS_CONN_PARAM}" ] && SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL}?${GOVPAY_DS_CONN_PARAM}"

    # Mappatura a variabili Spring

    export SPRING_DATASOURCE_URL
    export SPRING_DATASOURCE_USERNAME="${GOVPAY_DB_USER}"
    export SPRING_DATASOURCE_PASSWORD="${GOVPAY_DB_PASSWORD}"
    export SPRING_DATASOURCE_DRIVER_CLASS_NAME="${GOVPAY_DS_DRIVER_CLASS}"
    export SPRING_JPA_DATABASE_PLATFORM="${GOVPAY_HYBERNATE_DIALECT}"
    export SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT="${GOVPAY_HYBERNATE_DIALECT}"
    export SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE="${GOVPAY_FDR_MIN_POOL}"
    export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="${GOVPAY_FDR_MAX_POOL}"

    # Export per init_fdr_db.sh
    export GOVPAY_DB_TYPE GOVPAY_DB_SERVER GOVPAY_DB_NAME GOVPAY_DB_USER GOVPAY_DB_PASSWORD
    export GOVPAY_DS_JDBC_LIBS GOVPAY_DS_DRIVER_CLASS GOVPAY_DS_CONN_PARAM

    log_info "Database: ${GOVPAY_DB_TYPE} su ${GOVPAY_DB_SERVER}/${GOVPAY_DB_NAME}"

##############################################################################
# Inizializzazione Database
##############################################################################

if [ "${GOVPAY_FDR_POP_DB_SKIP:-TRUE}" != "TRUE" ] && [ -n "${GOVPAY_DB_TYPE}" ]; then
    log_info "Esecuzione script di inizializzazione database..."
    /usr/local/bin/init_fdr_db.sh
    if [ $? -ne 0 ]; then
        log_error "Inizializzazione database fallita"
        exit 1
    fi
fi

##############################################################################
# Configurazione URL Base pagoPA FDR API
##############################################################################

# Priorità 1: URL custom (se specificato, ha precedenza)
if [ -n "${GOVPAY_FDR_API_CUSTOMURL}" ]; then
    PAGOPA_FDR_BASE_URL="${GOVPAY_FDR_API_CUSTOMURL}"
    log_info "Utilizzo URL FDR API personalizzato: ${PAGOPA_FDR_BASE_URL}"
# Priorità 2: Selezione URL basata su ambiente
elif [ -n "${GOVPAY_FDR_API_ENV}" ]; then
    # Conversione a minuscolo per confronto case-insensitive
    FDR_ENV=$(echo "${GOVPAY_FDR_API_ENV}" | tr '[:upper:]' '[:lower:]')

    case "${FDR_ENV}" in
        prod|produzione)
            PAGOPA_FDR_BASE_URL="https://api.platform.pagopa.it/fdr-org/service/v1"
            log_info "Utilizzo ambiente FDR API PRODUZIONE"
            ;;
        collaudo|test|stage|uat)
            PAGOPA_FDR_BASE_URL="https://api.uat.platform.pagopa.it/fdr-org/service/v1"
            log_info "Utilizzo ambiente FDR API UAT/TEST"
            ;;
        *)
            log_error "Valore GOVPAY_FDR_API_ENV non valido: ${GOVPAY_FDR_API_ENV}"
            log_error "Valori ammessi: collaudo, test, stage, uat, prod, produzione"
            exit 1
            ;;
    esac
    log_info "URL Base FDR API: ${PAGOPA_FDR_BASE_URL}"
fi

# Validazione configurazione FDR API obbligatoria
if [ -z "${PAGOPA_FDR_BASE_URL}" ]; then
    log_error "Configurazione URL Base FDR API mancante"
    log_error "Impostare una di: GOVPAY_FDR_API_CUSTOMURL o GOVPAY_FDR_API_ENV"
    exit 1
fi
export PAGOPA_FDR_BASE_URL

if [ -z "${GOVPAY_FDR_API_SUBSCRIPTIONKEY}" ]; then
    log_error "Subscription Key FDR API mancante"
    log_error "Richiesta: GOVPAY_FDR_API_SUBSCRIPTIONKEY"
    exit 1
fi
export PAGOPA_FDR_SUBSCRIPTION_KEY="${GOVPAY_FDR_API_SUBSCRIPTIONKEY}"

##############################################################################
# Configurazione Cluster ID
##############################################################################

# Calcolo Cluster ID dall'indirizzo IP del container (da /etc/hosts)
GOVPAY_BATCH_CLUSTER_ID=$(grep -E "[[:space:]]${HOSTNAME}[[:space:]]*" /etc/hosts | head -n 1 | awk '{print $1}')
# Fallback a hostname se estrazione IP fallisce
[ -z "${GOVPAY_BATCH_CLUSTER_ID}" ] && GOVPAY_BATCH_CLUSTER_ID=$(hostname)
export GOVPAY_BATCH_CLUSTER_ID

##############################################################################
# Configurazione Integrazione GDE
##############################################################################

# Integrazione GDE disabilitata di default a meno che non venga fornito GOVPAY_FDR_GDE_URL
if [ -n "${GOVPAY_FDR_GDE_URL}" ]; then
    GOVPAY_GDE_ENABLED=true
    GOVPAY_GDE_BASE_URL="${GOVPAY_FDR_GDE_URL}"
    export GOVPAY_GDE_ENABLED GOVPAY_GDE_BASE_URL
    log_info "Integrazione GDE abilitata"
    log_info "URL GDE: ${GOVPAY_GDE_BASE_URL}"
else
    GOVPAY_GDE_ENABLED=false
    export GOVPAY_GDE_ENABLED
    log_info "Integrazione GDE disabilitata"
fi

##############################################################################
# Configurazione Modalità di Deploy
##############################################################################

# Verifica se modalità CRON è abilitata tramite GOVPAY_FDR_BATCH_USA_CRON
# Modalità CRON (TRUE) = esecuzione singola, schedulata da cron esterno (OS), profilo 'cron' attivo
# Modalità SCHEDULER INTERNO (FALSE) = batch daemon con scheduler Spring interno
# Valori ammessi per TRUE: si, yes, 1, true (case insensitive)

case "${GOVPAY_FDR_BATCH_USA_CRON,,}" in
    si|yes|1|true)
        log_info "Modalità deployment: CRON (schedulato da cron esterno/OS)"
        SPRING_MAIN_WEB_APPLICATION_TYPE="none"
        export SPRING_MAIN_WEB_APPLICATION_TYPE
        JAVA_OPTS="-Dspring.profiles.active=cron $JAVA_OPTS"
        ;;
    *)
        log_info "Modalità deployment: SCHEDULER INTERNO (auto-schedulato)"
        SERVER_PORT=${SERVER_PORT:-10001}

        # Conversione intervallo da minuti a millisecondi (default: 10 minuti = 600000 ms)
        INTERVALLO_MINUTI=${GOVPAY_FDR_BATCH_INTERVALLO_CRON:-10}
        SCHEDULER_FDRACQUISITIONJOB_FIXEDDELAYSTRING=$((INTERVALLO_MINUTI * 60 * 1000))

        export SERVER_PORT SCHEDULER_FDRACQUISITIONJOB_FIXEDDELAYSTRING
        log_info "Porta Actuator: ${SERVER_PORT}, Intervallo scheduler: ${INTERVALLO_MINUTI} minuti (${SCHEDULER_FDRACQUISITIONJOB_FIXEDDELAYSTRING}ms)"
        ;;
esac




##############################################################################
# Configurazione Memoria JVM (Percentuale RAM)
##############################################################################

JAVA_OPTS="${JAVA_OPTS:-}"
DEFAULT_MAX_RAM_PERCENTAGE=80

JVM_MEMORY_OPTS="-XX:MaxRAMPercentage=${GOVPAY_FDR_JVM_MAX_RAM_PERCENTAGE:-${DEFAULT_MAX_RAM_PERCENTAGE}}"
[ -n "${GOVPAY_FDR_JVM_INITIAL_RAM_PERCENTAGE}" ] && JVM_MEMORY_OPTS="$JVM_MEMORY_OPTS -XX:InitialRAMPercentage=${GOVPAY_FDR_JVM_INITIAL_RAM_PERCENTAGE}"
[ -n "${GOVPAY_FDR_JVM_MIN_RAM_PERCENTAGE}" ] && JVM_MEMORY_OPTS="$JVM_MEMORY_OPTS -XX:MinRAMPercentage=${GOVPAY_FDR_JVM_MIN_RAM_PERCENTAGE}"
[ -n "${GOVPAY_FDR_JVM_MAX_METASPACE_SIZE}" ] && JVM_MEMORY_OPTS="$JVM_MEMORY_OPTS -XX:MaxMetaspaceSize=${GOVPAY_FDR_JVM_MAX_METASPACE_SIZE}"
[ -n "${GOVPAY_FDR_JVM_MAX_DIRECT_MEMORY_SIZE}" ] && JVM_MEMORY_OPTS="$JVM_MEMORY_OPTS -XX:MaxDirectMemorySize=${GOVPAY_FDR_JVM_MAX_DIRECT_MEMORY_SIZE}"

JAVA_OPTS="${JAVA_OPTS} ${JVM_MEMORY_OPTS}"
export JAVA_OPTS

##############################################################################
# Riepilogo Configurazione
##############################################################################

log_info "========================================"
log_info "Riepilogo Configurazione"
log_info "========================================"
log_info "Database: ${SPRING_DATASOURCE_URL}"
log_info "Pool: ${SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE}/${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE}"
log_info "FDR API: ${PAGOPA_FDR_BASE_URL}"
log_info "GDE: ${GOVPAY_GDE_ENABLED}"
log_info "Cluster ID: ${GOVPAY_BATCH_CLUSTER_ID}"
log_info "Java: MaxRAMPercentage=${GOVPAY_FDR_JVM_MAX_RAM_PERCENTAGE:-${DEFAULT_MAX_RAM_PERCENTAGE}}%"
log_info "========================================"

##############################################################################
# Avvio Applicazione
##############################################################################

JAR_FILE=$(find /opt/govpay-fdr -name "*.jar" -type f | head -n 1)

if [ -z "${JAR_FILE}" ]; then
    log_error "Nessun file JAR trovato in /opt/govpay-fdr"
    exit 1
fi

log_info "Avvio: ${JAR_FILE}"
log_info "========================================"

exec java ${JAVA_OPTS} -jar "${JAR_FILE}"
