# Etapa 3 - Contas a pagar/receber e fluxo diario

## O que mudou

- Foi criado o modulo financeiro simples com:
  - cadastro de contas a pagar;
  - cadastro de contas a receber;
  - baixa parcial ou total;
  - lista de pendencias por vencimento.
- O sistema agora tem uma aba `Financeiro` para admin e gerente.
- O fechamento diario consolidado passou a considerar:
  - contas pagas;
  - contas recebidas;
  - contas pagas em dinheiro;
  - contas recebidas em dinheiro.
- O esperado do caixa agora soma recebimentos financeiros em dinheiro e desconta pagamentos financeiros em dinheiro.

## Arquivos principais

- `src/main/java/br/com/mercadotonico/desktop/DesktopFinanceService.java`
- `src/main/java/br/com/mercadotonico/desktop/DesktopCashReportService.java`
- `src/main/java/br/com/mercadotonico/desktop/DesktopApp.java`
- `src/main/java/br/com/mercadotonico/core/UserPermissions.java`
- `src/main/resources/db/migration/V006__finance_accounts.sql`
- `docs/migrations/rollback/V006__finance_accounts.sql`

## Como testar

1. Entrar com `admin` ou `gerente`.
2. Abrir a aba `Financeiro`.
3. Criar um lancamento `PAGAR`.
4. Criar um lancamento `RECEBER`.
5. Baixar um deles em `DINHEIRO`.
6. Baixar outro em `PIX` ou `CREDITO`.
7. Conferir:
   - a lista de pendencias;
   - o status `ABERTO`, `PARCIAL` ou `QUITADO`;
   - os cards de resumo da aba.
8. Abrir `Relatorios` e confirmar o bloco `Fluxo financeiro do dia`.
9. Fechar o caixa e validar o impacto das baixas em dinheiro no valor esperado.

## Risco

- O fluxo e simples e propositalmente conservador: uma tabela unica de lancamentos, sem parcelamento ou centro de custo.
- Isso reduz risco de operacao agora e deixa espaco para evolucao depois.

## Rollback

### Banco

- Executar `docs/migrations/rollback/V006__finance_accounts.sql`

### Codigo

- Reverter:
  - `DesktopFinanceService.java`
  - ajustes em `DesktopCashReportService.java`
  - ajustes em `DesktopApp.java`
  - ajuste de permissao em `UserPermissions.java`
  - migration `V006`
