-- Rollback manual e conservador para V002.
-- SQLite nao oferece DROP COLUMN simples em todas as versoes, entao o rollback recomendado e restaurar backup anterior.
-- Itens que podem ser revertidos sem recriar tabelas:

DROP TABLE IF EXISTS historico_preco;
DROP TABLE IF EXISTS app_log;

-- Para remover colunas adicionadas em usuarios/produtos, use restore do backup anterior a migration.
