ALTER TABLE usuarios ADD COLUMN desconto_maximo NUMERIC NOT NULL DEFAULT 0;
ALTER TABLE usuarios ADD COLUMN autoriza_preco_zero INTEGER NOT NULL DEFAULT 0;

ALTER TABLE produtos ADD COLUMN codigo_interno TEXT;
ALTER TABLE produtos ADD COLUMN controla_lote INTEGER NOT NULL DEFAULT 0;
ALTER TABLE produtos ADD COLUMN lote_padrao TEXT;
ALTER TABLE produtos ADD COLUMN observacoes TEXT;
ALTER TABLE produtos ADD COLUMN permite_preco_zero INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS historico_preco (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  produto_id INTEGER NOT NULL,
  preco_custo_anterior NUMERIC,
  preco_custo_novo NUMERIC,
  preco_venda_anterior NUMERIC,
  preco_venda_novo NUMERIC,
  alterado_por INTEGER,
  motivo TEXT,
  timestamp TEXT NOT NULL,
  FOREIGN KEY (produto_id) REFERENCES produtos(id),
  FOREIGN KEY (alterado_por) REFERENCES usuarios(id)
);

CREATE TABLE IF NOT EXISTS app_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nivel TEXT NOT NULL,
  contexto TEXT NOT NULL,
  mensagem TEXT NOT NULL,
  detalhes TEXT,
  criado_em TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_produtos_codigo_interno ON produtos(codigo_interno);
CREATE INDEX IF NOT EXISTS idx_historico_preco_produto ON historico_preco(produto_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_app_log_contexto ON app_log(contexto, criado_em);

UPDATE usuarios
SET role = CASE
  WHEN role = 'OPERADOR' THEN 'CAIXA'
  ELSE role
END;

UPDATE usuarios SET desconto_maximo = 30, autoriza_preco_zero = 1 WHERE role = 'ADMIN';

INSERT OR IGNORE INTO usuarios (nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero)
VALUES ('Operador Estoque', 'estoque1', '', 'ESTOQUE', 0, 0, 0);

UPDATE produtos
SET codigo_interno = COALESCE(codigo_interno, printf('P%05d', id))
WHERE codigo_interno IS NULL OR trim(codigo_interno) = '';
