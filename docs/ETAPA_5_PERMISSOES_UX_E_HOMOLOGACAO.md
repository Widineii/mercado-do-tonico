# Etapa 5 - Permissoes, UX e homologacao

## O que mudou

- O painel inicial agora respeita melhor o perfil:
  - `GERENTE` e `ADMIN` continuam com visao consolidada;
  - `CAIXA` passa a ver um painel operacional simples;
  - `ESTOQUE` passa a ver um painel focado em reposicao e validade.
- O PDV ganhou atalhos de teclado:
  - `F1` foco no codigo;
  - `F4` finalizar venda;
  - `F6` limpar carrinho;
  - `F7` suprimento;
  - `F8` sangria;
  - `F9` troca/devolucao;
  - `F10` reemitir comprovante;
  - `ESC` limpa o campo de codigo.
- O PDV agora mostra conferenca visual de pagamento:
  - faltante;
  - excedente;
  - pagamento conferido.
- Mensagens de erro ficaram mais amigaveis para:
  - campos numericos;
  - datas invalidas.
- Os logs de suporte passaram a registrar stack trace e usuario responsavel.

## Arquivos principais

- `src/main/java/br/com/mercadotonico/desktop/DesktopApp.java`
- `src/main/java/br/com/mercadotonico/core/SupportLogger.java`
- `src/test/java/br/com/mercadotonico/core/UserPermissionsTest.java`
- `docs/CHECKLIST_HOMOLOGACAO_FINAL.md`

## Como testar

1. Entrar com `caixa1` e confirmar:
   - sem abas de estoque, financeiro e relatorios;
   - painel inicial operacional.
2. Entrar com `estoque1` e confirmar:
   - sem aba PDV;
   - sem relatorios gerenciais;
   - painel focado em reposicao.
3. Entrar com `gerente` e confirmar:
   - todas as abas gerenciais liberadas.
4. No PDV:
   - usar `F1`, `F4`, `F6`, `F7`, `F8`, `F9`, `F10` e `ESC`;
   - validar o indicador de pagamento enquanto digita.
5. Informar um numero invalido e confirmar mensagem amigavel.
6. Informar uma data invalida e confirmar mensagem amigavel.
7. Verificar `data/logs/app.log` apos um erro operacional.

## Risco

- Esta etapa mexe mais em comportamento de tela do que em regra central.
- O risco principal e ergonomia; por isso o ideal e validar com teclado e uso continuo no PDV.

## Rollback

### Codigo

- Reverter:
  - ajustes em `DesktopApp.java`
  - ajustes em `SupportLogger.java`
  - testes e docs da etapa 5
