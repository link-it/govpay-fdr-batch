-- Test data for connettori table (used by ConnettoreService)
INSERT INTO connettori (cod_connettore, cod_proprieta, valore) VALUES ('PAGOPA_FDR', 'URL', 'http://localhost:8080/test/fdr-org/service/v1');
INSERT INTO connettori (cod_connettore, cod_proprieta, valore) VALUES ('PAGOPA_FDR', 'ABILITATO', 'true');
INSERT INTO connettori (cod_connettore, cod_proprieta, valore) VALUES ('PAGOPA_FDR', 'TIPOAUTENTICAZIONE', 'HTTP_HEADER');
INSERT INTO connettori (cod_connettore, cod_proprieta, valore) VALUES ('PAGOPA_FDR', 'HTTP_HEADER_AUTH_HEADER_NAME', 'Ocp-Apim-Subscription-Key');
INSERT INTO connettori (cod_connettore, cod_proprieta, valore) VALUES ('PAGOPA_FDR', 'HTTP_HEADER_AUTH_HEADER_VALUE', 'test-subscription-key');
INSERT INTO connettori (cod_connettore, cod_proprieta, valore) VALUES ('PAGOPA_FDR', 'CONNECTION_TIMEOUT', '10000');
INSERT INTO connettori (cod_connettore, cod_proprieta, valore) VALUES ('PAGOPA_FDR', 'READ_TIMEOUT', '30000');
-- Test data for GDE connector
INSERT INTO connettori (cod_connettore, cod_proprieta, valore) VALUES ('GDE', 'URL', 'http://localhost:10002/api/v1');
INSERT INTO connettori (cod_connettore, cod_proprieta, valore) VALUES ('GDE', 'ABILITATO', 'true');
INSERT INTO connettori (cod_connettore, cod_proprieta, valore) VALUES ('GDE', 'TIPOAUTENTICAZIONE', 'NONE');
