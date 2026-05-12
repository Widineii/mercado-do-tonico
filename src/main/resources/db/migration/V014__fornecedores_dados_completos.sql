-- Dados cadastrais adicionais do fornecedor (endereco completo + fiscal + observacoes).
ALTER TABLE fornecedores ADD COLUMN cep TEXT;
ALTER TABLE fornecedores ADD COLUMN cidade TEXT;
ALTER TABLE fornecedores ADD COLUMN estado TEXT;
ALTER TABLE fornecedores ADD COLUMN inscricao_estadual TEXT;
ALTER TABLE fornecedores ADD COLUMN observacoes TEXT;
