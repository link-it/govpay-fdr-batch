-- SQL Server DDL for FR_TEMP table

CREATE TABLE FR_TEMP (
    id                          BIGINT IDENTITY(1,1) PRIMARY KEY,
    id_psp                      VARCHAR(35),
    cod_psp                     VARCHAR(35),
    cod_dominio                 VARCHAR(35),
    cod_flusso                  VARCHAR(35),
    iur                         VARCHAR(35),
    data_ora_flusso             DATETIME2,
    data_regolamento            DATETIME2,
    data_ora_aggiornamento      DATETIME2,
    stato                       VARCHAR(35),
    numero_pagamenti            BIGINT,
    importo_totale_pagamenti    DECIMAL(15,2),
    cod_bic_riversamento        VARCHAR(35),
    ragione_sociale_psp         VARCHAR(70),
    ragione_sociale_dominio     VARCHAR(70),
    data_ora_pubblicazione      DATETIME2,
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

-- Add extended properties for documentation
EXEC sp_addextendedproperty
    @name = N'MS_Description',
    @value = N'Temporary table for storing FDR headers during batch processing',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'FR_TEMP';

EXEC sp_addextendedproperty
    @name = N'MS_Description',
    @value = N'Primary key',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'FR_TEMP',
    @level2type = N'COLUMN', @level2name = N'id';

EXEC sp_addextendedproperty
    @name = N'MS_Description',
    @value = N'Flow code (unique identifier)',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'FR_TEMP',
    @level2type = N'COLUMN', @level2name = N'cod_flusso';

EXEC sp_addextendedproperty
    @name = N'MS_Description',
    @value = N'FDR revision number',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'FR_TEMP',
    @level2type = N'COLUMN', @level2name = N'revisione';
