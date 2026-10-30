#!/bin/bash

##############################################################################
# Script di Inizializzazione Database GovPay FDR
# Questo script inizializza il database FDR eseguendo:
# 1. Liveness check (connessione TCP al database)
# 2. Readiness check (verifica esistenza delle tabelle Spring Batch e FR_TEMP)
# 3. Esecuzione degli script SQL delle tabelle mancanti
#
##############################################################################
# Niente 'set -x': il tracing scriverebbe nei log la password del database
# (file rc di SqlTool).
set -e

# Configurazione con valori di default
GOVPAY_FDR_POP_DB_SKIP=${GOVPAY_FDR_POP_DB_SKIP:-TRUE}
GOVPAY_FDR_DB_CHECK_TABLE=${GOVPAY_FDR_DB_CHECK_TABLE:-batch_job_execution_context}
GOVPAY_FDR_LIVE_DB_CHECK_SKIP=${GOVPAY_FDR_LIVE_DB_CHECK_SKIP:-FALSE}
GOVPAY_FDR_READY_DB_CHECK_SKIP=${GOVPAY_FDR_READY_DB_CHECK_SKIP:-FALSE}
GOVPAY_FDR_LIVE_DB_CHECK_MAX_RETRY=${GOVPAY_FDR_LIVE_DB_CHECK_MAX_RETRY:-30}
GOVPAY_FDR_LIVE_DB_CHECK_SLEEP_TIME=${GOVPAY_FDR_LIVE_DB_CHECK_SLEEP_TIME:-2}
GOVPAY_FDR_LIVE_DB_CHECK_CONNECT_TIMEOUT=${GOVPAY_FDR_LIVE_DB_CHECK_CONNECT_TIMEOUT:-5}
GOVPAY_FDR_READY_DB_CHECK_MAX_RETRY=${GOVPAY_FDR_READY_DB_CHECK_MAX_RETRY:-5}
GOVPAY_FDR_READY_DB_CHECK_SLEEP_TIME=${GOVPAY_FDR_READY_DB_CHECK_SLEEP_TIME:-2}

# Funzioni di logging
log_info() {
    echo "[INFO] $(date '+%Y-%m-%d %H:%M:%S') - $1"
}

log_warn() {
    echo "[WARN] $(date '+%Y-%m-%d %H:%M:%S') - $1"
}

log_error() {
    echo "[ERROR] $(date '+%Y-%m-%d %H:%M:%S') - $1" >&2
}

# Salta se l'inizializzazione è disabilitata
if [ "${GOVPAY_FDR_POP_DB_SKIP^^}" == "TRUE" ]; then
    log_info "Inizializzazione database saltata (GOVPAY_FDR_POP_DB_SKIP=TRUE)"
    exit 0
fi

log_info "========================================"
log_info "Inizializzazione Database GovPay FDR"
log_info "========================================"

# Estrazione server e porta da GOVPAY_DB_SERVER
IFS=':' read -r DB_HOST DB_PORT <<< "${GOVPAY_DB_SERVER}"

# Imposta porte di default se non specificate
if [ -z "${DB_PORT}" ] || [ "${DB_PORT}" == "${DB_HOST}" ]; then
    case "${GOVPAY_DB_TYPE}" in
        postgresql) DB_PORT=5432 ;;
        mysql|mariadb) DB_PORT=3306 ;;
        oracle) DB_PORT=1521 ;;
        *) DB_PORT=5432 ;;
    esac
fi

log_info "Tipo database: ${GOVPAY_DB_TYPE}"
log_info "Server database: ${DB_HOST}:${DB_PORT}"
log_info "Nome database: ${GOVPAY_DB_NAME}"

##############################################################################
# LIVENESS CHECK (Connessione TCP)
##############################################################################

if [ "${GOVPAY_FDR_LIVE_DB_CHECK_SKIP^^}" == "FALSE" ]; then
    log_info "Esecuzione liveness check (connessione TCP)..."
    RETRY=0
    DB_ALIVE=1

    while [ ${DB_ALIVE} -ne 0 ] && [ ${RETRY} -lt ${GOVPAY_FDR_LIVE_DB_CHECK_MAX_RETRY} ]; do
        # Con set -e un nc fallito fuori da un if chiuderebbe lo script al primo
        # tentativo, senza riprovare.
        if nc -w ${GOVPAY_FDR_LIVE_DB_CHECK_CONNECT_TIMEOUT} -z "${DB_HOST}" "${DB_PORT}"; then
            DB_ALIVE=0
        else
            DB_ALIVE=$?
        fi
        RETRY=$((RETRY + 1))

        if [ ${DB_ALIVE} -ne 0 ]; then
            log_info "Database non pronto, tentativo ${RETRY}/${GOVPAY_FDR_LIVE_DB_CHECK_MAX_RETRY}..."
            sleep ${GOVPAY_FDR_LIVE_DB_CHECK_SLEEP_TIME}
        fi
    done

    if [ ${DB_ALIVE} -ne 0 ]; then
        log_error "FATALE: Database non raggiungibile dopo ${GOVPAY_FDR_LIVE_DB_CHECK_MAX_RETRY} tentativi"
        exit 1
    fi

    log_info "Liveness check superato"
fi

##############################################################################
# COSTRUZIONE URL JDBC
##############################################################################

case "${GOVPAY_DB_TYPE}" in
    postgresql)
        JDBC_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${GOVPAY_DB_NAME}"
        START_TRANSACTION="START TRANSACTION;"
        ;;
    mysql|mariadb)
        JDBC_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${GOVPAY_DB_NAME}"
        START_TRANSACTION="START TRANSACTION;"
        ;;
    oracle)
        if [ "${GOVPAY_ORACLE_JDBC_URL_TYPE:-servicename}" == "servicename" ]; then
            JDBC_URL="jdbc:oracle:thin:@//${DB_HOST}:${DB_PORT}/${GOVPAY_DB_NAME}"
        else
            JDBC_URL="jdbc:oracle:thin:@${DB_HOST}:${DB_PORT}:${GOVPAY_DB_NAME}"
        fi
        START_TRANSACTION=""
        ;;
    *)
        log_error "Tipo database non supportato: ${GOVPAY_DB_TYPE}"
        exit 1
        ;;
esac

# Aggiunta parametri di connessione se presenti
if [ -n "${GOVPAY_DS_CONN_PARAM}" ]; then
    if [[ "${JDBC_URL}" == *"?"* ]]; then
        JDBC_URL="${JDBC_URL}&${GOVPAY_DS_CONN_PARAM}"
    else
        JDBC_URL="${JDBC_URL}?${GOVPAY_DS_CONN_PARAM}"
    fi
fi

log_info "URL JDBC: ${JDBC_URL}"

##############################################################################
# CREAZIONE FILE RC SQLTOOL
##############################################################################

# Il file rc contiene la password del database: viene rimosso all'uscita
# insieme alla copia temporanea degli script.
SQLTOOL_RC_FILE="$(mktemp /tmp/sqltool_fdr.XXXXXX)"
SQL_TMP_DIR="$(mktemp -d /tmp/fdr_sql.XXXXXX)"
trap 'rm -rf "${SQLTOOL_RC_FILE}" "${SQL_TMP_DIR}"' EXIT

cat > ${SQLTOOL_RC_FILE} <<EOSQLTOOL
urlid fdr_db
url ${JDBC_URL}
username ${GOVPAY_DB_USER}
password ${GOVPAY_DB_PASSWORD}
driver ${GOVPAY_DS_DRIVER_CLASS}
transiso TRANSACTION_READ_COMMITTED
charset UTF-8
EOSQLTOOL

# Comando SqlTool, usato sia dal readiness check sia dalla creazione delle
# tabelle: va definito qui perche' il readiness check si puo' saltare.
INVOCAZIONE_CLIENT="-Dfile.encoding=UTF-8 -cp ${GOVPAY_DS_JDBC_LIBS}/*:/opt/hsqldb-${HSQLDB_FULLVERSION}/hsqldb/lib/sqltool.jar org.hsqldb.cmdline.SqlTool --rcFile=${SQLTOOL_RC_FILE} "

##############################################################################
# READINESS CHECK (Esistenza Tabelle)
##############################################################################

# Conta le tabelle con il nome indicato (0 o 1); esce con errore se il
# database non risponde dopo GOVPAY_FDR_READY_DB_CHECK_MAX_RETRY tentativi.
conta_tabella() {
    local TABELLA="${1,,}"
    local CHECK_QUERY
    case "${GOVPAY_DB_TYPE}" in
        postgresql)
            CHECK_QUERY="SELECT count(*) FROM information_schema.tables WHERE LOWER(table_name)='${TABELLA}' AND LOWER(table_catalog)='${GOVPAY_DB_NAME,,}';"
            ;;
        mysql|mariadb)
            CHECK_QUERY="SELECT count(*) FROM information_schema.tables WHERE LOWER(table_name)='${TABELLA}' AND LOWER(table_schema)='${GOVPAY_DB_NAME,,}';"
            ;;
        oracle)
            CHECK_QUERY="SELECT count(*) FROM all_tables WHERE LOWER(table_name)='${TABELLA}' AND UPPER(owner)='${GOVPAY_DB_USER^^}';"
            ;;
    esac

    local RETRY=0
    local TABLE_COUNT
    while [ ${RETRY} -lt ${GOVPAY_FDR_READY_DB_CHECK_MAX_RETRY} ]; do
        TABLE_COUNT=$(java ${INVOCAZIONE_CLIENT} --sql="${CHECK_QUERY}" fdr_db 2>/dev/null | tail -1 | tr -d ' \n\r') || true
        if [[ "${TABLE_COUNT}" =~ ^[0-9]+$ ]]; then
            echo "${TABLE_COUNT}"
            return 0
        fi
        RETRY=$((RETRY + 1))
        if [ ${RETRY} -lt ${GOVPAY_FDR_READY_DB_CHECK_MAX_RETRY} ]; then
            log_info "Readiness check fallito, tentativo ${RETRY}/${GOVPAY_FDR_READY_DB_CHECK_MAX_RETRY}..." >&2
            sleep ${GOVPAY_FDR_READY_DB_CHECK_SLEEP_TIME}
        fi
    done
    log_error "FATALE: Readiness check fallito dopo ${GOVPAY_FDR_READY_DB_CHECK_MAX_RETRY} tentativi"
    return 1
}

# Le tabelle Spring Batch possono esistere gia' (database condiviso con altri
# batch GovPay) senza la tabella FR_TEMP di questo batch: i due controlli sono
# separati e si esegue solo lo script di cio' che manca.
CREA_SCHEMA_BATCH=TRUE
CREA_FR_TEMP=TRUE

if [ "${GOVPAY_FDR_READY_DB_CHECK_SKIP^^}" == "FALSE" ]; then
    log_info "Esecuzione readiness check (tabelle: ${GOVPAY_FDR_DB_CHECK_TABLE}, fr_temp)..."

    COUNT_BATCH=$(conta_tabella "${GOVPAY_FDR_DB_CHECK_TABLE}")
    COUNT_FR_TEMP=$(conta_tabella "fr_temp")
    log_info "Risultato readiness check: ${GOVPAY_FDR_DB_CHECK_TABLE}=${COUNT_BATCH}, fr_temp=${COUNT_FR_TEMP}"

    [ "${COUNT_BATCH}" -gt 0 ] && CREA_SCHEMA_BATCH=FALSE
    [ "${COUNT_FR_TEMP}" -gt 0 ] && CREA_FR_TEMP=FALSE

    if [ "${CREA_SCHEMA_BATCH}" == "FALSE" ] && [ "${CREA_FR_TEMP}" == "FALSE" ]; then
        log_info "Database già inizializzato, esecuzione SQL saltata"
        exit 0
    fi
fi

##############################################################################
# ESECUZIONE SCRIPT SQL
##############################################################################

log_info "Inizializzazione database in corso..."

# Determina directory SQL (mariadb usa gli script mysql)
SQL_DIR="${GOVPAY_DB_TYPE}"
[ "${GOVPAY_DB_TYPE}" == "mariadb" ] && SQL_DIR="mysql"

# Schema dei metadati Spring Batch: file ufficiale della libreria, estratto in
# fase di packaging in /opt/sql/spring-batch e mai modificato. Ha un file per
# vendor, mariadb compreso, quindi qui si usa GOVPAY_DB_TYPE e non SQL_DIR.
BATCH_SCHEMA="/opt/sql/spring-batch/schema-${GOVPAY_DB_TYPE}.sql"

# Tracciabilita' di cosa contiene /opt/sql
if [ -f /opt/sql/VERSION ]; then
    log_info "Script SQL a bordo immagine:"
    grep -v '^#' /opt/sql/VERSION | grep -v '^$' | while read -r riga; do log_info "  ${riga}"; done
fi

# Script da eseguire: lo schema dei metadati Spring Batch e/o il DDL della
# tabella applicativa FR_TEMP. Controllarne l'esistenza qui fa fallire
# l'inizializzazione con un messaggio chiaro invece che dentro SqlTool.
SCRIPT_DA_ESEGUIRE=()
[ "${CREA_SCHEMA_BATCH}" == "TRUE" ] && SCRIPT_DA_ESEGUIRE+=("${BATCH_SCHEMA}")
[ "${CREA_FR_TEMP}" == "TRUE" ] && SCRIPT_DA_ESEGUIRE+=("/opt/sql/${SQL_DIR}/create.sql")

for SQL_FILE in "${SCRIPT_DA_ESEGUIRE[@]}"; do
    if [ ! -f "${SQL_FILE}" ]; then
        log_error "Script SQL non trovato: ${SQL_FILE}"
        log_error "File disponibili in /opt/sql:"
        ls -la /opt/sql/ 2>/dev/null || echo "  directory /opt/sql non trovata"
        if [ -d "/opt/sql/${SQL_DIR}" ]; then
            log_error "File in /opt/sql/${SQL_DIR}:"
            ls -la "/opt/sql/${SQL_DIR}/" 2>/dev/null
        fi
        if [ -d /opt/sql/spring-batch ]; then
            log_error "File in /opt/sql/spring-batch:"
            ls -la /opt/sql/spring-batch/ 2>/dev/null
        fi
        exit 1
    fi
done

# Copia degli script nella directory temporanea, con un nome per ruolo
INCLUDE_SQL=""
if [ "${CREA_SCHEMA_BATCH}" == "TRUE" ]; then
    cp "${BATCH_SCHEMA}" "${SQL_TMP_DIR}/schema-batch.sql"
    INCLUDE_SQL="${INCLUDE_SQL}\\i ${SQL_TMP_DIR}/schema-batch.sql"$'\n'
    log_info "Creazione delle tabelle Spring Batch (${BATCH_SCHEMA})"
fi
if [ "${CREA_FR_TEMP}" == "TRUE" ]; then
    cp "/opt/sql/${SQL_DIR}/create.sql" "${SQL_TMP_DIR}/create-fr-temp.sql"
    INCLUDE_SQL="${INCLUDE_SQL}\\i ${SQL_TMP_DIR}/create-fr-temp.sql"$'\n'
    log_info "Creazione della tabella fr_temp (/opt/sql/${SQL_DIR}/create.sql)"
fi

# Applica trasformazioni specifiche per vendor
case "${GOVPAY_DB_TYPE}" in
    mysql|mariadb)
        log_info "Applicazione trasformazioni MySQL/MariaDB..."
        # Rimuove quote escaped negli statement COMMENT
        sed -i -e "/COMMENT/s%\\\\'% %g" "${SQL_TMP_DIR}"/*.sql
        ;;
    oracle)
        log_info "Applicazione trasformazioni Oracle..."
        # Abilita modalità raw per trigger e funzioni
        sed -i -r -e '/^CREATE( OR REPLACE)? (TRIGGER|FUNCTION|PROCEDURE)/i .' \
                  -e 's/^\/$/.\n:;/' "${SQL_TMP_DIR}"/*.sql
        ;;
esac


# Esecuzione script SQL
log_info "Esecuzione script SQL in corso..."

# Con set -e un errore di SqlTool chiuderebbe lo script senza il messaggio
# d'errore: l'exit code si raccoglie con ||.
SQL_EXIT_CODE=0
java ${INVOCAZIONE_CLIENT} \
    --continueOnErr=false \
    fdr_db <<EOSQL || SQL_EXIT_CODE=$?
SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
${START_TRANSACTION}
${INCLUDE_SQL}COMMIT;
EOSQL

if [ ${SQL_EXIT_CODE} -eq 0 ]; then
    log_info "========================================"
    log_info "Inizializzazione database completata con successo"
    log_info "========================================"
else
    log_error "========================================"
    log_error "Inizializzazione database fallita con codice d'uscita: ${SQL_EXIT_CODE}"
    log_error "========================================"
fi

exit ${SQL_EXIT_CODE}
