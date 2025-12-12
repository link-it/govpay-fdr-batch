<p align="center">
<img src="https://www.link.it/wp-content/uploads/2025/01/logo-govpay.svg" alt="GovPay Logo" width="200"/>
</p>

# GovPay FDR Batch

[![GitHub](https://img.shields.io/badge/GitHub-link--it%2Fgovpay--fdr--batch-blue?logo=github)](https://github.com/link-it/govpay-fdr-batch)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

Batch Spring Boot per l'acquisizione dei **Flussi di Rendicontazione (FDR)** da pagoPA.

## Cos'è GovPay FDR Batch

GovPay FDR Batch è un componente del progetto [GovPay](https://github.com/link-it/govpay) che si occupa dell'acquisizione automatica dei flussi di rendicontazione dal servizio FDR di pagoPA.

### Funzionalità principali

- Acquisizione automatica dei flussi di rendicontazione da pagoPA
- Supporto multi-database: PostgreSQL, MySQL/MariaDB, Oracle
- Modalità di deployment flessibili (daemon o esecuzione singola)
- Integrazione opzionale con GDE (Giornale degli Eventi)
- Health check e monitoraggio tramite Spring Boot Actuator
- Gestione automatica del recovery per job bloccati

## Versioni disponibili

- `latest` - ultima versione stabile
- `1.0.1`

Storico completo delle modifiche consultabile nel [ChangeLog](https://github.com/link-it/govpay-fdr-batch/blob/main/ChangeLog) del progetto.

## Quick Start

```bash
docker pull linkitaly/govpay-fdr-batch:latest
```

## Documentazione

- [README e istruzioni di configurazione](https://github.com/link-it/govpay-fdr-batch/blob/main/README.md)
- [Documentazione Docker](https://github.com/link-it/govpay-fdr-batch/blob/main/docker/DOCKER.md)
- [Dockerfile](https://github.com/link-it/govpay-fdr-batch/blob/main/docker/govpay-fdr/Dockerfile.github)

## Licenza

GovPay FDR Batch è rilasciato con licenza [GPL v3](https://www.gnu.org/licenses/gpl-3.0).

## Supporto

- **Issues**: [GitHub Issues](https://github.com/link-it/govpay-fdr-batch/issues)
- **GovPay**: [govpay.readthedocs.io](https://govpay.readthedocs.io/)

---

Sviluppato da [Link.it s.r.l.](https://www.link.it)
