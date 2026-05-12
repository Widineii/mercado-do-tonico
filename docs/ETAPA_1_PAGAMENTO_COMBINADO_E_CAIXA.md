# Etapa 1 - Pagamento combinado e ajustes de caixa

## O que mudou

- O PDV agora aceita pagamento combinado entre `DINHEIRO`, `DEBITO`, `CREDITO`, `PIX` e `FIADO`.
- A venda so pode ser concluida quando a soma das formas de pagamento for exatamente igual ao total final da venda.
- Quando houver valor em `FIADO`, o operador precisa selecionar um cliente antes de concluir.
- Cada forma de pagamento passou a ser gravada separadamente na tabela `venda_pagamentos`.
- O fechamento de caixa usa um resumo consolidado do dia com:
  - abertura;
  - vendas por forma de pagamento;
  - sangria;
  - suprimento;
  - valor contado no fechamento;
  - divergencia entre esperado e contado.
- A aba `Relatorios` ganhou um quadro de fechamento diario consolidado.

## Arquivos principais

- `src/main/java/br/com/mercadotonico/core/PaymentAllocationService.java`
- `src/main/java/br/com/mercadotonico/desktop/DesktopCashReportService.java`
- `src/main/java/br/com/mercadotonico/desktop/DesktopApp.java`
- `src/main/java/br/com/mercadotonico/db/MigrationRunner.java`
- `src/main/resources/db/migration/V004__payment_flow_hardening.sql`
- `docs/migrations/rollback/V004__payment_flow_hardening.sql`

## Como testar

1. Abrir o desktop pelo atalho `ABRIR_MERCADO_TUNICO_DESKTOP.bat`.
2. Entrar com um usuario de caixa.
3. Abrir um caixa com fundo inicial.
4. Adicionar produtos ao carrinho.
5. Informar valores em duas ou mais formas de pagamento.
6. Confirmar que:
   - a venda so fecha quando a soma bate exatamente com o total;
   - ao deixar diferenca, o sistema mostra erro amigavel;
   - ao usar `FIADO`, a venda exige cliente;
   - ao concluir, a venda grava os pagamentos separados.
7. Fazer uma `SANGRIA` e um `SUPRIMENTO`.
8. Fechar o caixa informando o valor contado.
9. Conferir na aba `Relatorios` o quadro `Fechamento diario consolidado`.

## Risco

- O maior risco desta etapa fica no `DesktopApp.java`, que ainda concentra bastante regra de tela.
- Como mitigacao, a validacao de pagamento e o resumo de caixa foram extraidos para servicos dedicados e cobertos por teste.

## Rollback

### Banco

- Remover os indices criados em `V004__payment_flow_hardening.sql` usando:
  - `docs/migrations/rollback/V004__payment_flow_hardening.sql`

### Codigo

- Reverter os arquivos da etapa 1:
  - `PaymentAllocationService.java`
  - `DesktopCashReportService.java`
  - ajustes no `DesktopApp.java`
  - inclusao da migration `V004`

## Resultado esperado

- Venda com pagamento combinado operando de forma consistente.
- Fechamento de caixa mais confiavel para uso diario.
- Base pronta para a Etapa 2 de troca e devolucao.
