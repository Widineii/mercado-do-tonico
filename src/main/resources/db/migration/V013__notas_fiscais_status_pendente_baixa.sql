PRAGMA foreign_keys = ON;

-- =====================================================================
-- V013: Status da NF-e importada (fila x baixa no estoque)
-- ---------------------------------------------------------------------
-- PENDENTE: XML registrado, ainda nao deu entrada no estoque.
-- BAIXADO: operador confirmou baixa; itens ja entraram no estoque.
-- Registros antigos (antes desta versao) passam a BAIXADO pois a
-- importacao ja aplicava estoque na mesma hora.
-- =====================================================================

ALTER TABLE notas_fiscais ADD COLUMN status TEXT;

UPDATE notas_fiscais SET status = 'BAIXADO' WHERE status IS NULL;

CREATE INDEX IF NOT EXISTS idx_notas_fiscais_status ON notas_fiscais(status);
