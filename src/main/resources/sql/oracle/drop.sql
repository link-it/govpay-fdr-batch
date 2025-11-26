-- Oracle - Drop FR_TEMP table and related objects

-- Drop trigger first
DROP TRIGGER FR_TEMP_TRG;

-- Drop table (cascade constraints to handle any foreign keys)
DROP TABLE FR_TEMP CASCADE CONSTRAINTS;

-- Drop sequence
DROP SEQUENCE FR_TEMP_SEQ;
