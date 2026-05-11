PRAGMA foreign_keys = ON;

-- =====================================================================
-- V012: Campos "fiado_de" e "convenio" na tabela clientes
-- ---------------------------------------------------------------------
-- fiado_de: indica em nome de quem o cliente pega fiado (ex: conjuge,
--   filho, gerente da empresa). Util quando varias pessoas usam a
--   mesma conta de cadastro - ex: "Maria, esposa do Joao".
--
-- convenio: nome do convenio/empresa parceira ao qual o cliente
--   pertence (ex: "Prefeitura Municipal", "Construtora XYZ"). Permite
--   filtrar clientes por convenio nos relatorios e fechamento.
-- =====================================================================

ALTER TABLE clientes ADD COLUMN fiado_de TEXT;
ALTER TABLE clientes ADD COLUMN convenio TEXT;

CREATE INDEX IF NOT EXISTS idx_clientes_convenio ON clientes(convenio);
