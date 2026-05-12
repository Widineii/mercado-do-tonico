# Manual Rapido - Mercearia do Tunico

## Abrir o sistema

Para usar em um computador, abra:

- `ABRIR_SERVIDOR_CAIXA.vbs`

Esse atalho abre o sistema sem mostrar a janela preta do CMD.

## Uso com dois computadores

Quando for usar dois caixas:

1. No PC principal, abra `ABRIR_SERVIDOR_CAIXA.vbs`.
2. No segundo PC, configure `config/desktop.properties` com o caminho do banco do servidor.
3. No segundo PC, abra `ABRIR_CAIXA_CLIENTE.vbs`.

Exemplo de configuracao do cliente:

```properties
mercado.db.url=jdbc:sqlite:\\\\NOME-PC-SERVIDOR\mercado\data\mercado-tonico.db
```

## Backup

Use `BACKUP_MERCADO_TUNICO.bat` no PC principal.

O backup salva uma copia do banco em `backups`.

## Restaurar backup

Antes de usar `RESTORE_MERCADO_TUNICO.bat`, feche o sistema em todos os computadores.

Restaurar com o sistema aberto pode sobrescrever dados em uso.

## Operacao diaria

1. Fazer login.
2. Abrir caixa.
3. Registrar vendas no PDV.
4. Conferir estoque e convênio quando necessario.
5. Fechar caixa no fim do dia.
6. Fazer backup.

## Senhas

Troque as senhas padrao antes do uso real na loja.

Operacoes sensiveis devem ser autorizadas com a senha real de um usuario ADMIN ou GERENTE.
