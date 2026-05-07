# Etapa 2 - Troca e devolucao

## O que mudou

- Foi criado um fluxo operacional de `Troca / devolucao` no PDV com PIN de gerente.
- A devolucao pode ser total ou parcial por item da venda original.
- O sistema valida o saldo devolvivel de cada item para impedir devolucao acima do que foi vendido.
- A devolucao retorna o item corretamente ao estoque e grava movimentacao com trilha de auditoria.
- A devolucao financeira agora suporta:
  - `DINHEIRO`
  - `PIX`
  - `DEBITO`
  - `CREDITO`
  - `ABATER_FIADO`
- A troca gera `vale troca` com codigo unico e saldo controlado.
- O PDV passou a aceitar `CREDITO_TROCA` como forma de pagamento em nova venda.
- O fechamento diario passou a descontar devolucao em dinheiro do valor esperado do caixa.

## Arquivos principais

- `src/main/java/br/com/mercadotonico/desktop/DesktopReturnService.java`
- `src/main/java/br/com/mercadotonico/desktop/DesktopApp.java`
- `src/main/java/br/com/mercadotonico/desktop/DesktopCashReportService.java`
- `src/main/java/br/com/mercadotonico/core/PaymentAllocationService.java`
- `src/main/resources/db/migration/V005__returns_and_store_credit.sql`
- `docs/migrations/rollback/V005__returns_and_store_credit.sql`

## Como testar

1. Abrir o sistema desktop.
2. Entrar com gerente ou caixa.
3. Abrir um caixa.
4. Fazer uma venda com pelo menos um item.
5. Acionar `Troca / devolucao`.
6. Informar o PIN de gerente.
7. Informar o ID da venda original.
8. Testar devolucao parcial:
   - devolver apenas parte da quantidade;
   - escolher `DINHEIRO`;
   - confirmar que o estoque volta e a operacao entra no caixa.
9. Testar troca:
   - devolver item da venda;
   - escolher tipo `TROCA`;
   - confirmar que o sistema gera um codigo de vale troca.
10. Fazer nova venda e usar `Credito troca` + `Codigo vale troca`.
11. Verificar em `Relatorios` a lista de devolucoes do dia e o fechamento consolidado.

## Risco

- O maior risco da etapa fica no acoplamento da tela Swing com dialogs de operacao, porque ainda estamos em um desktop monolitico.
- Como mitigacao, a regra critica foi levada para `DesktopReturnService` com testes automatizados.

## Rollback

### Banco

- Executar `docs/migrations/rollback/V005__returns_and_store_credit.sql`

### Codigo

- Reverter:
  - `DesktopReturnService.java`
  - ajustes no `DesktopApp.java`
  - ajustes no `DesktopCashReportService.java`
  - suporte a `CREDITO_TROCA` no `PaymentAllocationService.java`
  - migration `V005`
