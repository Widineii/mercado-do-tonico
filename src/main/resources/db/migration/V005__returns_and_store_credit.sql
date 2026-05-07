CREATE TABLE IF NOT EXISTS devolucoes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  venda_id INTEGER NOT NULL,
  caixa_id INTEGER NOT NULL,
  operador_id INTEGER NOT NULL,
  cliente_id INTEGER,
  tipo TEXT NOT NULL,
  forma_destino TEXT NOT NULL,
  valor_total NUMERIC NOT NULL,
  motivo TEXT NOT NULL,
  criado_em TEXT NOT NULL,
  FOREIGN KEY (venda_id) REFERENCES vendas(id),
  FOREIGN KEY (caixa_id) REFERENCES caixas(id),
  FOREIGN KEY (operador_id) REFERENCES usuarios(id),
  FOREIGN KEY (cliente_id) REFERENCES clientes(id)
);

CREATE TABLE IF NOT EXISTS devolucao_itens (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  devolucao_id INTEGER NOT NULL,
  venda_item_id INTEGER NOT NULL,
  produto_id INTEGER NOT NULL,
  quantidade NUMERIC NOT NULL,
  valor_unitario NUMERIC NOT NULL,
  FOREIGN KEY (devolucao_id) REFERENCES devolucoes(id),
  FOREIGN KEY (venda_item_id) REFERENCES venda_itens(id),
  FOREIGN KEY (produto_id) REFERENCES produtos(id)
);

CREATE TABLE IF NOT EXISTS creditos_troca (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  codigo TEXT NOT NULL UNIQUE,
  cliente_id INTEGER,
  devolucao_id INTEGER NOT NULL,
  saldo NUMERIC NOT NULL,
  status TEXT NOT NULL DEFAULT 'ABERTO',
  criado_em TEXT NOT NULL,
  utilizado_em TEXT,
  operador_id INTEGER NOT NULL,
  FOREIGN KEY (cliente_id) REFERENCES clientes(id),
  FOREIGN KEY (devolucao_id) REFERENCES devolucoes(id),
  FOREIGN KEY (operador_id) REFERENCES usuarios(id)
);

CREATE INDEX IF NOT EXISTS idx_devolucoes_venda_data ON devolucoes(venda_id, criado_em);
CREATE INDEX IF NOT EXISTS idx_devolucao_itens_venda_item ON devolucao_itens(venda_item_id);
CREATE INDEX IF NOT EXISTS idx_creditos_troca_codigo ON creditos_troca(codigo, status);
