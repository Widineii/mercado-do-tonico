# Etapa 4 - Comprovante e lote/validade

## O que mudou

- Cada venda agora gera comprovante simples em:
  - `TXT`
  - `PDF`
- Os comprovantes ficam registrados em banco para reemissao.
- O PDV ganhou a acao `Reemitir comprovante`.
- Foi criada uma visao operacional de `Lotes e validades` baseada nas entradas reais de estoque.
- O produto agora guarda a validade mais proxima conhecida entre os lotes recebidos.
- A importacao XML deixou de gravar data de emissao da NF como validade do produto.

## Arquivos principais

- `src/main/java/br/com/mercadotonico/desktop/DesktopReceiptService.java`
- `src/main/java/br/com/mercadotonico/desktop/DesktopInventoryService.java`
- `src/main/java/br/com/mercadotonico/desktop/DesktopApp.java`
- `src/main/resources/db/migration/V007__receipts_and_lot_indexes.sql`
- `docs/migrations/rollback/V007__receipts_and_lot_indexes.sql`

## Como testar

1. Abrir o PDV e concluir uma venda.
2. Confirmar a mensagem com caminho do `TXT` e do `PDF`.
3. Verificar os arquivos em `data/comprovantes`.
4. Usar `Reemitir comprovante` com o ID de uma venda existente.
5. Ir em `Relatorios` e conferir `Comprovantes gerados`.
6. Na aba `Estoque`, registrar entrada com:
   - lote;
   - validade;
   - documento.
7. Conferir a grade `Lotes e validades`.
8. Validar em `Relatorios` a secao `Validades proximas por lote`.

## Risco

- O comprovante e simples e intencionalmente leve, sem integracao fiscal.
- O controle de lote/validade e minimo viavel: registra entrada, exibe consulta e prioriza a validade mais proxima no produto.

## Rollback

### Banco

- Executar `docs/migrations/rollback/V007__receipts_and_lot_indexes.sql`

### Codigo

- Reverter:
  - `DesktopReceiptService.java`
  - ajustes no `DesktopInventoryService.java`
  - ajustes no `DesktopApp.java`
  - migration `V007`
