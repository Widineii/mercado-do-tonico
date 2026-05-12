-- Endereco detalhado + celular + tipo de documento + ativo (cadastro estilo ERP).
ALTER TABLE fornecedores ADD COLUMN numero TEXT;
ALTER TABLE fornecedores ADD COLUMN bairro TEXT;
ALTER TABLE fornecedores ADD COLUMN complemento TEXT;
ALTER TABLE fornecedores ADD COLUMN celular TEXT;
ALTER TABLE fornecedores ADD COLUMN documento_tipo TEXT NOT NULL DEFAULT 'CNPJ';
ALTER TABLE fornecedores ADD COLUMN ativo INTEGER NOT NULL DEFAULT 1;
