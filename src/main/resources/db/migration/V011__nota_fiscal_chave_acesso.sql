PRAGMA foreign_keys = ON;

-- =====================================================================
-- V011: Coluna chave_acesso em notas_fiscais
-- ---------------------------------------------------------------------
-- A aba "XML NF-e" do desktop lista as notas importadas mostrando a
-- chave de acesso (44 digitos da NF-e). A coluna nunca tinha sido
-- migrada no schema, por isso o desktop quebrava ao montar a aba.
-- Migration idempotente: se a coluna ja existir, MigrationRunner ignora.
-- =====================================================================

ALTER TABLE notas_fiscais ADD COLUMN chave_acesso TEXT;

CREATE INDEX IF NOT EXISTS idx_notas_fiscais_chave ON notas_fiscais(chave_acesso);
