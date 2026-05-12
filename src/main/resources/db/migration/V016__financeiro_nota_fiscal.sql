-- Vincula lancamento financeiro a NF-e importada (conta a pagar gerada na baixa do XML).
ALTER TABLE financeiro_lancamentos ADD COLUMN nota_fiscal_id INTEGER REFERENCES notas_fiscais(id);

CREATE INDEX IF NOT EXISTS idx_financeiro_nota_fiscal ON financeiro_lancamentos(nota_fiscal_id);
