ALTER TABLE usuarios ADD COLUMN senha_temporaria INTEGER NOT NULL DEFAULT 0;

UPDATE usuarios
SET senha_temporaria = 1
WHERE login IN ('admin', 'gerente', 'caixa1', 'caixa2', 'estoque1');
