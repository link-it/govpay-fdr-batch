-- MySQL DDL for FR_TEMP table

CREATE TABLE FR_TEMP (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    id_psp                      VARCHAR(35),
    cod_psp                     VARCHAR(35),
    cod_dominio                 VARCHAR(35),
    cod_flusso                  VARCHAR(35),
    iur                         VARCHAR(35),
    data_ora_flusso             DATETIME(3),
    data_regolamento            DATETIME(3),
    data_ora_aggiornamento      DATETIME(3),
    stato                       VARCHAR(35),
    numero_pagamenti            BIGINT,
    importo_totale_pagamenti    DOUBLE,
    cod_bic_riversamento        VARCHAR(35),
    ragione_sociale_psp         VARCHAR(70),
    ragione_sociale_dominio     VARCHAR(70),
    data_ora_pubblicazione      DATETIME(3),
    revisione                   BIGINT
) ENGINE=InnoDB CHARACTER SET latin1 COLLATE latin1_general_cs
  COMMENT='Temporary table for storing FDR headers during batch processing';

-- Create indexes for common queries
CREATE INDEX idx_fr_temp_cod_flusso ON FR_TEMP(cod_flusso);
CREATE INDEX idx_fr_temp_cod_dominio ON FR_TEMP(cod_dominio);
CREATE INDEX idx_fr_temp_cod_psp ON FR_TEMP(cod_psp);
CREATE INDEX idx_fr_temp_stato ON FR_TEMP(stato);

-- Optimized composite indexes for batch processing queries
-- For findByCodDominioOrderByDataOraPubblicazioneAsc() in Step 3 & 4
CREATE INDEX idx_fr_temp_dominio_publ ON FR_TEMP(cod_dominio, data_ora_pubblicazione);

-- For existsByCodDominioAndCodFlussoAndIdPspAndRevisione() in Step 2 writer
CREATE INDEX idx_fr_temp_exists_check ON FR_TEMP(cod_dominio, cod_flusso, id_psp, revisione);
