PRAGMA foreign_keys = ON;

-- =====================================================================
-- V010: Cadastro automatico de produtos por codigo de barras (EAN/GTIN)
-- ---------------------------------------------------------------------
-- Adiciona campos exigidos por software fiscal/PDV de mercado:
--   marca, fabricante, NCM (Nomenclatura Comum do Mercosul), CEST,
--   imagem do produto e timestamp de cadastro.
-- Cria tambem a tabela produto_lookup_cache, usada para evitar
-- consultas repetidas as APIs externas (OpenFoodFacts/Cosmos Bluesoft).
-- Cada EAN consultado fica com seu payload bruto e a fonte, e
-- consultas posteriores pegam direto do cache (TTL na aplicacao).
-- =====================================================================

ALTER TABLE produtos ADD COLUMN marca         TEXT;
ALTER TABLE produtos ADD COLUMN fabricante    TEXT;
ALTER TABLE produtos ADD COLUMN ncm           TEXT;
ALTER TABLE produtos ADD COLUMN cest          TEXT;
ALTER TABLE produtos ADD COLUMN imagem_url    TEXT;
ALTER TABLE produtos ADD COLUMN cadastrado_em TEXT;

UPDATE produtos
   SET cadastrado_em = COALESCE(cadastrado_em, datetime('now'))
 WHERE cadastrado_em IS NULL;

CREATE INDEX IF NOT EXISTS idx_produtos_codigo_barras ON produtos(codigo_barras);
CREATE INDEX IF NOT EXISTS idx_produtos_ncm           ON produtos(ncm);
CREATE INDEX IF NOT EXISTS idx_produtos_marca         ON produtos(marca);

CREATE TABLE IF NOT EXISTS produto_lookup_cache (
  barcode      TEXT PRIMARY KEY,
  source       TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  fetched_at   TEXT NOT NULL,
  found        INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_lookup_cache_fetched ON produto_lookup_cache(fetched_at);
