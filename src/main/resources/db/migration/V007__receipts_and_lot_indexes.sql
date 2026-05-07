CREATE TABLE IF NOT EXISTS comprovantes_venda (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  venda_id INTEGER NOT NULL UNIQUE,
  arquivo_txt TEXT NOT NULL,
  arquivo_pdf TEXT NOT NULL,
  gerado_em TEXT NOT NULL,
  FOREIGN KEY (venda_id) REFERENCES vendas(id)
);

CREATE INDEX IF NOT EXISTS idx_entradas_estoque_validade_lote ON entradas_estoque(validade, lote, produto_id);
