# FDR Batch Integration Tests

Questa directory contiene i test di integrazione end-to-end per il batch di acquisizione FDR.

## Test Suite

### FdrDownloadIntegrationTest
Test completi per il download e l'elaborazione di un FR con 10 rendicontazioni.

**Scenari testati:**
- ✅ Download completo di un FR con 10 rendicontazioni
- ✅ Processamento dei metadati del flusso
- ✅ Validazione e salvataggio dei pagamenti
- ✅ Verifica delle statistiche del FR
- ✅ Controllo degli stati delle rendicontazioni

**Copertura:**
- Step 3: Processamento metadati FDR
- Step 4: Processamento pagamenti
- Persistenza su database (Fr e Rendicontazioni)
- Validazioni di business logic

### FdrAnomaliesIntegrationTest
Test per la gestione delle anomalie e casi edge.

**Scenari testati:**
- ✅ IUV non trovato → ALTRO_INTERMEDIARIO
- ✅ Rendicontazioni duplicate → ANOMALA
- ✅ Discordanza importo totale → FR ANOMALA
- ✅ Discordanza numero pagamenti → FR ANOMALA
- ✅ Scenario misto (OK + ALTRO_INTERMEDIARIO + ANOMALA)

**Copertura:**
- Gestione anomalie di business
- Stati delle rendicontazioni (OK, ANOMALA, ALTRO_INTERMEDIARIO)
- Stati del FR (ACCETTATA, ANOMALA)
- Controlli di consistenza

## Esecuzione

```bash
# Esegui tutti i test di integrazione
mvn test -Dtest=*IntegrationTest

# Esegui solo FdrDownloadIntegrationTest
mvn test -Dtest=FdrDownloadIntegrationTest

# Esegui solo FdrAnomaliesIntegrationTest
mvn test -Dtest=FdrAnomaliesIntegrationTest
```

## Struttura dei Test

Ogni test segue il pattern AAA (Arrange-Act-Assert):

1. **Arrange**:
   - Mock delle API REST (OrganizationsApi)
   - Creazione dati di test (Domini, Versamenti, Pagamenti)
   - Setup del database

2. **Act**:
   - Processamento tramite FdrMetadataProcessor
   - Scrittura tramite FdrMetadataWriter
   - Processamento pagamenti tramite FdrPaymentsProcessor
   - Scrittura pagamenti tramite FdrPaymentsWriter

3. **Assert**:
   - Verifica dati salvati nel database
   - Controllo stati e anomalie
   - Validazione logica di business

## Dati di Test

### Domini
- `codDominio`: "12345678901"
- `ragioneSociale`: "Comune di Test"

### FDR
- `codFlusso`: "2025-01-27PSP001-0001" (test normali)
- `codFlusso`: "2025-01-27PSP001-ANOM" (test anomalie)
- `codPsp`: "PSP001"
- `revisione`: 1

### Pagamenti
- IUV: "IUV0000000001", "IUV0000000002", ...
- IUR: "IUR0000000001", "IUR0000000002", ...
- Importo: 10.50 per pagamento
- Esito: 0 (EXECUTED)

## Coverage

I test coprono:
- **Repository**: Fr, Rendicontazione, Dominio, Versamento, Pagamento, FrTemp
- **Processors**: FdrMetadataProcessor, FdrPaymentsProcessor
- **Writers**: FdrMetadataWriter, FdrPaymentsWriter
- **Business Logic**: Validazioni, gestione anomalie, stati

## Note

- I test usano `@Transactional` per rollback automatico
- GDE è disabilitato nei test (`govpay.gde.enabled=false`)
- Spring Batch job auto-start è disabilitato (`spring.batch.job.enabled=false`)
- Database H2 in-memory per test isolati
