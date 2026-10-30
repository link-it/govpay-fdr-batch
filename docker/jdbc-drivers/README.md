# Driver JDBC

I driver JDBC non sono inclusi nel jar del batch né nell'immagine e vanno forniti a parte. Collocare i jar dei driver in questa directory e montarla nel container in `/opt/jdbc-drivers`: l'entrypoint imposta `LOADER_PATH` su quella directory (o su `GOVPAY_DS_JDBC_LIBS`), così il `PropertiesLauncher` di Spring Boot carica i driver all'avvio. La stessa directory è usata da SqlTool per creare le tabelle all'avvio.

Versioni gestite da [govpay-bom](https://github.com/link-it/govpay-bom):

| Database | Driver | Licenza |
|----------|--------|---------|
| PostgreSQL | `wget https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar` | BSD-2-Clause |
| MySQL e MariaDB | `wget https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/26.10.0/mysql-connector-j-26.10.0.jar` | GPL-2.0 con eccezione FOSS |
| Oracle | `wget https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.26.3.0.0/ojdbc11-23.26.3.0.0.jar` | Oracle Free Use Terms and Conditions (FUTC): verificarne i termini prima dell'uso in produzione |

Per MariaDB l'entrypoint usa il driver MySQL (`com.mysql.cj.jdbc.Driver`). L'immagine non supporta SQL Server (`GOVPAY_DB_TYPE`); il driver SQL Server serve solo eseguendo il jar fuori dal container.

Se all'avvio compare "No suitable driver found", verificare che il jar del driver sia nella directory montata e leggibile dall'utente del container e che `GOVPAY_DB_TYPE` corrisponda al database in uso.
