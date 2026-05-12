# Backup e restore

## Backup

1. Feche o sistema.
2. Execute `BACKUP_MERCADO_TUNICO.bat`.
3. O arquivo sera salvo em `backups\`.

## Restore

1. Feche o sistema.
2. Execute `RESTORE_MERCADO_TUNICO.bat` para restaurar o backup mais recente automaticamente.
3. Se preferir manual, copie o backup desejado para `data\mercado-tunico.db` (ou renomeie um `.db` legado `mercado-tonico.db` se ainda for o caso).
4. Reabra o sistema.

## Teste recomendado (5 minutos)

1. Faça um backup com `BACKUP_MERCADO_TUNICO.bat`.
2. Renomeie temporariamente `data\mercado-tunico.db` para `data\mercado-tunico_teste.db`.
3. Rode `RESTORE_MERCADO_TUNICO.bat`.
4. Abra o sistema e valide login e listagem de produtos.
5. Apague `data\mercado-tunico_teste.db` se estiver tudo ok.

## Rollback de migration

- Para alteracoes com novas colunas em SQLite, o rollback mais seguro e restaurar o backup anterior.
- O roteiro manual da fase atual esta em `docs/migrations/rollback/V002__security_and_operations.sql`.
