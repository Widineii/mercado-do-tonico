PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS usuarios (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nome TEXT NOT NULL,
  login TEXT NOT NULL UNIQUE,
  senha_hash TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('ADMIN','GERENTE','CAIXA','ESTOQUE')),
  pin_hash TEXT,
  ativo INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS categorias (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nome TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS fornecedores (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  razao_social TEXT NOT NULL,
  nome_fantasia TEXT,
  cnpj TEXT UNIQUE,
  telefone TEXT,
  email TEXT,
  endereco TEXT,
  contato TEXT
);

CREATE TABLE IF NOT EXISTS produtos (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nome TEXT NOT NULL,
  codigo_barras TEXT UNIQUE,
  sku TEXT UNIQUE,
  categoria TEXT,
  unidade TEXT NOT NULL DEFAULT 'un',
  preco_custo NUMERIC NOT NULL DEFAULT 0,
  preco_venda NUMERIC NOT NULL DEFAULT 0,
  estoque_atual NUMERIC NOT NULL DEFAULT 0,
  estoque_minimo NUMERIC NOT NULL DEFAULT 0,
  localizacao TEXT,
  validade TEXT,
  fornecedor_id INTEGER,
  ativo INTEGER NOT NULL DEFAULT 1,
  FOREIGN KEY (fornecedor_id) REFERENCES fornecedores(id)
);

CREATE TABLE IF NOT EXISTS caixas (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  numero INTEGER NOT NULL UNIQUE,
  status TEXT NOT NULL DEFAULT 'FECHADO' CHECK (status IN ('FECHADO','ABERTO')),
  operador_atual_id INTEGER,
  abertura_valor NUMERIC NOT NULL DEFAULT 0,
  abertura_timestamp TEXT,
  FOREIGN KEY (operador_atual_id) REFERENCES usuarios(id)
);

CREATE TABLE IF NOT EXISTS clientes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nome TEXT NOT NULL,
  cpf TEXT UNIQUE,
  telefone TEXT,
  endereco TEXT,
  limite_credito NUMERIC NOT NULL DEFAULT 0,
  observacoes TEXT,
  bloqueado INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS vendas (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  caixa_id INTEGER NOT NULL,
  operador_id INTEGER NOT NULL,
  cliente_id INTEGER,
  total NUMERIC NOT NULL,
  desconto NUMERIC NOT NULL DEFAULT 0,
  forma_pagamento TEXT NOT NULL,
  timestamp TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'CONCLUIDA',
  FOREIGN KEY (caixa_id) REFERENCES caixas(id),
  FOREIGN KEY (operador_id) REFERENCES usuarios(id),
  FOREIGN KEY (cliente_id) REFERENCES clientes(id)
);

CREATE TABLE IF NOT EXISTS venda_itens (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  venda_id INTEGER NOT NULL,
  produto_id INTEGER NOT NULL,
  quantidade NUMERIC NOT NULL,
  preco_unitario NUMERIC NOT NULL,
  custo_unitario NUMERIC NOT NULL DEFAULT 0,
  FOREIGN KEY (venda_id) REFERENCES vendas(id),
  FOREIGN KEY (produto_id) REFERENCES produtos(id)
);

CREATE TABLE IF NOT EXISTS venda_pagamentos (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  venda_id INTEGER NOT NULL,
  forma TEXT NOT NULL,
  valor NUMERIC NOT NULL,
  FOREIGN KEY (venda_id) REFERENCES vendas(id)
);

CREATE TABLE IF NOT EXISTS movimentacao_estoque (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  produto_id INTEGER NOT NULL,
  tipo TEXT NOT NULL,
  quantidade NUMERIC NOT NULL,
  referencia_id INTEGER,
  operador_id INTEGER,
  timestamp TEXT NOT NULL,
  observacao TEXT,
  FOREIGN KEY (produto_id) REFERENCES produtos(id),
  FOREIGN KEY (operador_id) REFERENCES usuarios(id)
);

CREATE TABLE IF NOT EXISTS fiado (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cliente_id INTEGER NOT NULL,
  venda_id INTEGER,
  valor NUMERIC NOT NULL,
  valor_pago NUMERIC NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'ABERTO',
  data_criacao TEXT NOT NULL,
  FOREIGN KEY (cliente_id) REFERENCES clientes(id),
  FOREIGN KEY (venda_id) REFERENCES vendas(id)
);

CREATE TABLE IF NOT EXISTS fiado_pagamentos (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  fiado_id INTEGER NOT NULL,
  valor NUMERIC NOT NULL,
  data TEXT NOT NULL,
  operador_id INTEGER NOT NULL,
  FOREIGN KEY (fiado_id) REFERENCES fiado(id),
  FOREIGN KEY (operador_id) REFERENCES usuarios(id)
);

CREATE TABLE IF NOT EXISTS notas_fiscais (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  fornecedor_id INTEGER,
  numero_nf TEXT,
  data TEXT,
  xml_path TEXT,
  total NUMERIC NOT NULL DEFAULT 0,
  importado_em TEXT NOT NULL,
  FOREIGN KEY (fornecedor_id) REFERENCES fornecedores(id)
);

CREATE TABLE IF NOT EXISTS caixa_operacoes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  caixa_id INTEGER NOT NULL,
  tipo TEXT NOT NULL,
  valor NUMERIC NOT NULL,
  motivo TEXT,
  operador_id INTEGER NOT NULL,
  timestamp TEXT NOT NULL,
  FOREIGN KEY (caixa_id) REFERENCES caixas(id),
  FOREIGN KEY (operador_id) REFERENCES usuarios(id)
);

CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  usuario_id INTEGER,
  acao TEXT NOT NULL,
  detalhe TEXT,
  timestamp TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_produtos_busca ON produtos(nome, codigo_barras, sku);
CREATE INDEX IF NOT EXISTS idx_vendas_timestamp ON vendas(timestamp);
CREATE INDEX IF NOT EXISTS idx_mov_estoque_timestamp ON movimentacao_estoque(timestamp);
CREATE INDEX IF NOT EXISTS idx_fiado_cliente ON fiado(cliente_id, status);

INSERT OR IGNORE INTO caixas (numero, status) VALUES (1, 'FECHADO');
INSERT OR IGNORE INTO caixas (numero, status) VALUES (2, 'FECHADO');

INSERT OR IGNORE INTO categorias (nome) VALUES
('Mercearia'), ('Bebidas'), ('Limpeza'), ('Hortifruti'), ('Frios'), ('Padaria');

INSERT OR IGNORE INTO fornecedores (id, razao_social, nome_fantasia, cnpj, telefone, email, endereco, contato)
VALUES (1, 'Distribuidora Tonico Alimentos LTDA', 'Tonico Distribuidora', '11222333000181', '(11) 4002-8922', 'compras@tonico.local', 'Rua do Comercio, 100', 'Marcia');

INSERT OR IGNORE INTO produtos (nome, codigo_barras, sku, categoria, unidade, preco_custo, preco_venda, estoque_atual, estoque_minimo, localizacao, validade, fornecedor_id, ativo) VALUES
('Arroz Tipo 1 5kg', '7891000000011', 'ARR-5KG', 'Mercearia', 'un', 22.90, 31.90, 24, 8, 'Corredor 1', '2026-12-31', 1, 1),
('Feijao Carioca 1kg', '7891000000028', 'FEI-1KG', 'Mercearia', 'un', 5.90, 8.49, 38, 10, 'Corredor 1', '2026-10-20', 1, 1),
('Acucar Cristal 1kg', '7891000000035', 'ACU-1KG', 'Mercearia', 'un', 3.10, 4.79, 40, 12, 'Corredor 1', '2027-01-15', 1, 1),
('Cafe Tradicional 500g', '7891000000042', 'CAF-500', 'Mercearia', 'un', 10.50, 15.99, 18, 6, 'Corredor 2', '2026-09-10', 1, 1),
('Leite Integral 1L', '7891000000059', 'LEI-1L', 'Bebidas', 'un', 3.80, 5.49, 48, 15, 'Geladeira 1', '2026-06-01', 1, 1),
('Refrigerante Cola 2L', '7891000000066', 'REF-2L', 'Bebidas', 'un', 5.20, 8.99, 30, 8, 'Corredor 3', '2026-11-30', 1, 1),
('Detergente Neutro 500ml', '7891000000073', 'DET-500', 'Limpeza', 'un', 1.60, 2.79, 50, 10, 'Corredor 4', '2027-03-10', 1, 1),
('Sabao em Po 1kg', '7891000000080', 'SAB-1KG', 'Limpeza', 'un', 6.80, 10.99, 20, 8, 'Corredor 4', '2027-04-10', 1, 1),
('Banana Prata kg', '7891000000097', 'BAN-KG', 'Hortifruti', 'kg', 2.90, 5.99, 12, 5, 'Banca 1', '2026-05-20', 1, 1),
('Pao Frances kg', '7891000000103', 'PAO-KG', 'Padaria', 'kg', 7.00, 13.99, 10, 4, 'Padaria', '2026-05-06', 1, 1);
