CREATE TABLE IF NOT EXISTS financeiro_lancamentos (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tipo TEXT NOT NULL,
  descricao TEXT NOT NULL,
  parceiro TEXT,
  categoria TEXT,
  valor_total NUMERIC NOT NULL,
  valor_baixado NUMERIC NOT NULL DEFAULT 0,
  vencimento TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ABERTO',
  forma_baixa TEXT,
  observacao TEXT,
  criado_por INTEGER NOT NULL,
  criado_em TEXT NOT NULL,
  baixado_em TEXT,
  FOREIGN KEY (criado_por) REFERENCES usuarios(id)
);

CREATE INDEX IF NOT EXISTS idx_financeiro_tipo_status_vencimento ON financeiro_lancamentos(tipo, status, vencimento);
CREATE INDEX IF NOT EXISTS idx_financeiro_baixado_em ON financeiro_lancamentos(baixado_em);
