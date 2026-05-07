# Validacao final guiada

## 1) Validacao automatica (5 a 10 min)

1. Execute `VALIDAR_SISTEMA.bat`.
2. Confirme no final:
   - Testes: OK
   - Build: OK
   - Pastas operacionais: OK

## 2) Validacao operacional (10 a 20 min)

1. Abra `ABRIR_MERCADO_TONICO_DESKTOP.bat`.
2. Entre com `admin` e troque a senha temporaria.
3. Repita para `gerente`, `caixa1` e `estoque1`.
4. No perfil `caixa1`:
   - abrir caixa;
   - vender item simples;
   - vender com pagamento combinado;
   - fechar caixa.
5. No perfil `estoque1`:
   - importar XML de NF-e;
   - validar aumento de estoque.
6. Execute `BACKUP_MERCADO_TONICO.bat`.
7. Execute `RESTORE_MERCADO_TONICO.bat`.
8. Reabra o sistema e confirme login + produtos.

## 3) Critério de pronto

- Sem erro nos testes/build.
- Sem erro no login dos perfis.
- Venda e fechamento funcionando.
- XML atualizando estoque.
- Backup e restore validados.
