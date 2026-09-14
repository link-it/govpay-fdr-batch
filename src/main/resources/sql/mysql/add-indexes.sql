-- MySQL: Add optimized indexes for batch performance
-- Execute this script on existing database to add missing indexes

-- ==============================================================================
-- TABELLA FR (Flussi di Rendicontazione permanenti)
-- ==============================================================================

-- For existsByCodDominioAndCodFlussoAndCodPspAndRevisione() in FdrHeadersWriter
CREATE INDEX idx_fr_exists_check ON FR(cod_dominio, cod_flusso, cod_psp, revisione);

-- For queries filtering by domain
CREATE INDEX idx_fr_cod_dominio ON FR(cod_dominio);

-- ==============================================================================
-- TABELLA PAGAMENTI
-- ==============================================================================

-- For findAllByCodDominioAndIuv() in FdrPaymentsWriter
CREATE INDEX idx_pag_dom_iuv ON PAGAMENTI(cod_dominio, iuv);

-- For findAllByCodDominioAndIuvAndIur() in FdrPaymentsWriter
CREATE INDEX idx_pag_dom_iuv_iur ON PAGAMENTI(cod_dominio, iuv, iur);

-- For findAllByCodDominioAndIuvAndIurAndIndiceDati() in FdrPaymentsWriter
CREATE INDEX idx_pag_dom_iuv_iur_idx ON PAGAMENTI(cod_dominio, iuv, iur, indice_dati);

-- For queries filtering by IUV
CREATE INDEX idx_pag_iuv ON PAGAMENTI(iuv);

-- ==============================================================================
-- TABELLA RENDICONTAZIONI
-- ==============================================================================

-- For duplicate detection in FdrPaymentsWriter
CREATE INDEX idx_rnd_iuv_iur_idx ON RENDICONTAZIONI(iuv, iur, indice_dati);

-- For queries by IUV
CREATE INDEX idx_rnd_iuv ON RENDICONTAZIONI(iuv);

-- For queries by IUR
CREATE INDEX idx_rnd_iur ON RENDICONTAZIONI(iur);

-- For foreign key to FR table
CREATE INDEX idx_rnd_id_fr ON RENDICONTAZIONI(id_fr);

-- ==============================================================================
-- TABELLA DOMINI
-- ==============================================================================

-- For findDominioWithMaxDataOraPubblicazioneAsc() in FdrHeadersReader
CREATE INDEX idx_domini_scarica_fr ON DOMINI(scarica_fr);

-- Composite index for domain lookup by code
CREATE INDEX idx_domini_cod_dominio ON DOMINI(cod_dominio);

-- ==============================================================================
-- ANALYZE TABLES
-- ==============================================================================

ANALYZE TABLE FR_TEMP;
ANALYZE TABLE FR;
ANALYZE TABLE PAGAMENTI;
ANALYZE TABLE RENDICONTAZIONI;
ANALYZE TABLE DOMINI;
