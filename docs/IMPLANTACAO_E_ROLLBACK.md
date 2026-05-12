# Implantacao e rollback

## Implantacao

1. Confirmar Java 17 no ambiente.
2. Conferir a pasta do projeto:
   - `C:\Users\widin\OneDrive\Documentos\New project 3`
3. Executar o atalho:
   - `ABRIR_MERCADO_TUNICO_DESKTOP.bat`
4. Validar login inicial.
5. Confirmar criacao do banco em `data/mercado-tunico.db` (instalacoes antigas podem ainda usar `data/mercado-tonico.db`; o aplicativo reconhece o arquivo legado se existir so ele).
6. Confirmar geracao da pasta `data/logs`.
7. Rodar uma venda de teste.
8. Rodar um backup manual.

## Arquivos importantes

- Banco: `data/mercado-tunico.db` (legado: `data/mercado-tonico.db`)
- Logs: `data/logs/app.log`
- Comprovantes: `data/comprovantes`
- Backup: `BACKUP_MERCADO_TUNICO.bat`

## Rollback operacional

1. Fechar o aplicativo.
2. Guardar copia do banco atual.
3. Restaurar o ultimo backup valido do banco.
4. Reabrir o sistema.
5. Validar login, estoque, vendas e caixas.

## Rollback tecnico

- Reverter codigo da release.
- Aplicar rollback das migrations especificas em `docs/migrations/rollback/` apenas se necessario e com copia de seguranca do banco.

## Validacao minima apos rollback

- Login
- Abertura de caixa
- Venda simples
- Fechamento de caixa
- Consulta de estoque
