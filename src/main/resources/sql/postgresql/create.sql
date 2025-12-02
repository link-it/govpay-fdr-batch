-- PostgreSQL DDL for FR_TEMP table

CREATE TABLE FR_TEMP (
    id                          BIGSERIAL PRIMARY KEY,
    id_psp                      VARCHAR(35),
    cod_psp                     VARCHAR(35),
    cod_dominio                 VARCHAR(35),
    cod_flusso                  VARCHAR(35),
    iur                         VARCHAR(35),
    data_ora_flusso             TIMESTAMP,
    data_regolamento            TIMESTAMP,
    data_ora_aggiornamento      TIMESTAMP,
    stato                       VARCHAR(35),
    numero_pagamenti            BIGINT,
    importo_totale_pagamenti    DOUBLE PRECISION,
    cod_bic_riversamento        VARCHAR(35),
    ragione_sociale_psp         VARCHAR(70),
    ragione_sociale_dominio     VARCHAR(70),
    data_ora_pubblicazione      TIMESTAMP,
    revisione                   BIGINT
);

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

-- Add table and column comments
COMMENT ON TABLE FR_TEMP IS 'Temporary table for storing FDR headers during batch processing';
COMMENT ON COLUMN FR_TEMP.id IS 'Primary key';
COMMENT ON COLUMN FR_TEMP.id_psp IS 'PSP identifier';
COMMENT ON COLUMN FR_TEMP.cod_psp IS 'PSP code';
COMMENT ON COLUMN FR_TEMP.cod_dominio IS 'Domain code';
COMMENT ON COLUMN FR_TEMP.cod_flusso IS 'Flow code';
COMMENT ON COLUMN FR_TEMP.iur IS 'Unique payment reference';
COMMENT ON COLUMN FR_TEMP.revisione IS 'FDR revision number';
