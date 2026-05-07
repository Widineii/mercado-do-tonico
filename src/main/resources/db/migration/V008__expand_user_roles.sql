PRAGMA foreign_keys = OFF;

ALTER TABLE usuarios RENAME TO usuarios_old;

CREATE TABLE usuarios (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nome TEXT NOT NULL,
  login TEXT NOT NULL UNIQUE,
  senha_hash TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('ADMIN','GERENTE','CAIXA','ESTOQUE')),
  pin_hash TEXT,
  ativo INTEGER NOT NULL DEFAULT 1,
  desconto_maximo NUMERIC NOT NULL DEFAULT 0,
  autoriza_preco_zero INTEGER NOT NULL DEFAULT 0
);

INSERT INTO usuarios (
  id,
  nome,
  login,
  senha_hash,
  role,
  pin_hash,
  ativo,
  desconto_maximo,
  autoriza_preco_zero
)
SELECT
  id,
  nome,
  login,
  senha_hash,
  CASE
    WHEN role = 'OPERADOR' THEN 'CAIXA'
    ELSE role
  END,
  pin_hash,
  COALESCE(ativo, 1),
  COALESCE(desconto_maximo, 0),
  COALESCE(autoriza_preco_zero, 0)
FROM usuarios_old;

DROP TABLE usuarios_old;

PRAGMA foreign_keys = ON;
