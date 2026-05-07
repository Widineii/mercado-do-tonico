CREATE TABLE IF NOT EXISTS entradas_estoque (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  produto_id INTEGER NOT NULL,
  quantidade NUMERIC NOT NULL,
  custo_unitario NUMERIC NOT NULL,
  lote TEXT,
  validade TEXT,
  documento TEXT,
  observacao TEXT,
  operador_id INTEGER NOT NULL,
  criado_em TEXT NOT NULL,
  FOREIGN KEY (produto_id) REFERENCES produtos(id),
  FOREIGN KEY (operador_id) REFERENCES usuarios(id)
);

CREATE TABLE IF NOT EXISTS inventario_ajustes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  produto_id INTEGER NOT NULL,
  saldo_sistema NUMERIC NOT NULL,
  saldo_contado NUMERIC NOT NULL,
  diferenca NUMERIC NOT NULL,
  motivo TEXT NOT NULL,
  operador_id INTEGER NOT NULL,
  criado_em TEXT NOT NULL,
  FOREIGN KEY (produto_id) REFERENCES produtos(id),
  FOREIGN KEY (operador_id) REFERENCES usuarios(id)
);

CREATE INDEX IF NOT EXISTS idx_entradas_estoque_produto ON entradas_estoque(produto_id, criado_em);
CREATE INDEX IF NOT EXISTS idx_inventario_ajustes_produto ON inventario_ajustes(produto_id, criado_em);
