-- Oracle DDL for FR_TEMP table
-- Auto-increment implemented via SEQUENCE and TRIGGER

-- Create sequence for primary key
CREATE SEQUENCE FR_TEMP_SEQ
    START WITH 1
    INCREMENT BY 1
    NOCACHE
    NOCYCLE;

-- Create table
CREATE TABLE FR_TEMP (
    id                          NUMBER PRIMARY KEY,
    id_psp                      VARCHAR2(35 CHAR),
    cod_psp                     VARCHAR2(35 CHAR),
    cod_dominio                 VARCHAR2(35 CHAR),
    cod_flusso                  VARCHAR2(35 CHAR),
    iur                         VARCHAR2(35 CHAR),
    data_ora_flusso             TIMESTAMP,
    data_regolamento            TIMESTAMP,
    data_ora_aggiornamento      TIMESTAMP,
    stato                       VARCHAR2(35 CHAR),
    numero_pagamenti            NUMBER,
    importo_totale_pagamenti    BINARY_DOUBLE,
    cod_bic_riversamento        VARCHAR2(35 CHAR),
    ragione_sociale_psp         VARCHAR2(70 CHAR),
    ragione_sociale_dominio     VARCHAR2(70 CHAR),
    data_ora_pubblicazione      TIMESTAMP,
    revisione                   NUMBER
);

-- Create trigger for auto-increment
CREATE OR REPLACE TRIGGER FR_TEMP_TRG
BEFORE INSERT ON FR_TEMP
FOR EACH ROW
BEGIN
    IF :NEW.id IS NULL THEN
        SELECT FR_TEMP_SEQ.NEXTVAL INTO :NEW.id FROM DUAL;
    END IF;
END;
/

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

-- Add comments
COMMENT ON TABLE FR_TEMP IS 'Temporary table for storing FDR headers during batch processing';
COMMENT ON COLUMN FR_TEMP.id IS 'Primary key';
COMMENT ON COLUMN FR_TEMP.id_psp IS 'PSP identifier';
COMMENT ON COLUMN FR_TEMP.cod_psp IS 'PSP code';
COMMENT ON COLUMN FR_TEMP.cod_dominio IS 'Domain code';
COMMENT ON COLUMN FR_TEMP.cod_flusso IS 'Flow code';
COMMENT ON COLUMN FR_TEMP.iur IS 'Unique payment reference';
COMMENT ON COLUMN FR_TEMP.revisione IS 'FDR revision number';
