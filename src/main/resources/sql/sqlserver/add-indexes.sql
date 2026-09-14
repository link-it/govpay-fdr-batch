-- SQL Server: Add optimized indexes for batch performance
-- Execute this script on existing database to add missing indexes

-- ==============================================================================
-- TABELLA FR (Flussi di Rendicontazione permanenti)
-- ==============================================================================

-- For existsByCodDominioAndCodFlussoAndCodPspAndRevisione() in FdrHeadersWriter
-- This composite index speeds up the existence check in Step 2
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_fr_exists_check' AND object_id = OBJECT_ID('FR'))
BEGIN
    CREATE INDEX idx_fr_exists_check ON FR(cod_dominio, cod_flusso, cod_psp, revisione);
END;

-- For queries filtering by domain
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_fr_cod_dominio' AND object_id = OBJECT_ID('FR'))
BEGIN
    CREATE INDEX idx_fr_cod_dominio ON FR(cod_dominio);
END;

-- ==============================================================================
-- TABELLA PAGAMENTI
-- ==============================================================================

-- For findAllByCodDominioAndIuv() in FdrPaymentsWriter
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_pag_dom_iuv' AND object_id = OBJECT_ID('PAGAMENTI'))
BEGIN
    CREATE INDEX idx_pag_dom_iuv ON PAGAMENTI(cod_dominio, iuv);
END;

-- For findAllByCodDominioAndIuvAndIur() in FdrPaymentsWriter
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_pag_dom_iuv_iur' AND object_id = OBJECT_ID('PAGAMENTI'))
BEGIN
    CREATE INDEX idx_pag_dom_iuv_iur ON PAGAMENTI(cod_dominio, iuv, iur);
END;

-- For findAllByCodDominioAndIuvAndIurAndIndiceDati() in FdrPaymentsWriter
-- Most specific query - covers all combinations
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_pag_dom_iuv_iur_idx' AND object_id = OBJECT_ID('PAGAMENTI'))
BEGIN
    CREATE INDEX idx_pag_dom_iuv_iur_idx ON PAGAMENTI(cod_dominio, iuv, iur, indice_dati);
END;

-- For queries filtering by IUV (common lookup)
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_pag_iuv' AND object_id = OBJECT_ID('PAGAMENTI'))
BEGIN
    CREATE INDEX idx_pag_iuv ON PAGAMENTI(iuv);
END;

-- ==============================================================================
-- TABELLA RENDICONTAZIONI
-- ==============================================================================

-- For duplicate detection in FdrPaymentsWriter
-- Used to check if rendicontazione already exists
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_rnd_iuv_iur_idx' AND object_id = OBJECT_ID('RENDICONTAZIONI'))
BEGIN
    CREATE INDEX idx_rnd_iuv_iur_idx ON RENDICONTAZIONI(iuv, iur, indice_dati);
END;

-- For queries by IUV
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_rnd_iuv' AND object_id = OBJECT_ID('RENDICONTAZIONI'))
BEGIN
    CREATE INDEX idx_rnd_iuv ON RENDICONTAZIONI(iuv);
END;

-- For queries by IUR
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_rnd_iur' AND object_id = OBJECT_ID('RENDICONTAZIONI'))
BEGIN
    CREATE INDEX idx_rnd_iur ON RENDICONTAZIONI(iur);
END;

-- For foreign key to FR table
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_rnd_id_fr' AND object_id = OBJECT_ID('RENDICONTAZIONI'))
BEGIN
    CREATE INDEX idx_rnd_id_fr ON RENDICONTAZIONI(id_fr);
END;

-- ==============================================================================
-- TABELLA DOMINI
-- ==============================================================================

-- For findDominioWithMaxDataOraPubblicazione() in FdrHeadersReader
-- Used in Step 2 to find enabled domains
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_domini_scarica_fr' AND object_id = OBJECT_ID('DOMINI'))
BEGIN
    CREATE INDEX idx_domini_scarica_fr ON DOMINI(scarica_fr);
END;

-- Composite index for domain lookup by code
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_domini_cod_dominio' AND object_id = OBJECT_ID('DOMINI'))
BEGIN
    CREATE INDEX idx_domini_cod_dominio ON DOMINI(cod_dominio);
END;

-- ==============================================================================
-- UPDATE STATISTICS
-- ==============================================================================
-- Update statistics after adding indexes for query planner optimization

UPDATE STATISTICS FR_TEMP WITH FULLSCAN;
UPDATE STATISTICS FR WITH FULLSCAN;
UPDATE STATISTICS PAGAMENTI WITH FULLSCAN;
UPDATE STATISTICS RENDICONTAZIONI WITH FULLSCAN;
UPDATE STATISTICS DOMINI WITH FULLSCAN;
