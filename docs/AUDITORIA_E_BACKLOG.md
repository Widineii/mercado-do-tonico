# Diagnostico atual

## O que ja existe

- Aplicativo desktop Swing com login e operacao local em SQLite.
- Fluxos basicos de PDV: abertura de caixa, carrinho, venda, sangria, suprimento, cancelamento e fechamento.
- Cadastros de produtos, fornecedores e clientes.
- Estoque com movimentacao basica e importacao XML NF-e simples.
- Fiado e alguns relatorios operacionais.
- Auditoria simples e seed inicial.

## Lacunas atuais identificadas

- Regras criticas estavam concentradas em um unico arquivo grande (`DesktopApp.java`), sem base de migracoes versionadas.
- Controle de acesso incompleto: so havia `ADMIN` e `OPERADOR`.
- Validacoes insuficientes em desconto, preco zero, estoque negativo e motivos obrigatorios.
- Sem historico formal de alteracao de preco/custo.
- Sem mecanismo simples de log de suporte e sem rotina documentada de backup/restore.
- Sem testes automatizados.
- Sem custo medio nas entradas por XML.
- Sem controle de pagamentos combinados, devolucao/troca, contas a pagar/receber, validade/lote operacional completo e comprovante de venda.

# Backlog priorizado

## Must

- Base de migracoes versionadas e reversao documentada.
- Perfis `ADMIN`, `GERENTE`, `CAIXA`, `ESTOQUE`.
- Limite de desconto por perfil.
- Bloqueio de venda com preco zero para perfil nao autorizado.
- Bloqueio de estoque negativo em venda e ajuste.
- Historico de preco/custo.
- Codigo interno automatico para produto sem codigo de barras.
- Logs de suporte e trilha de auditoria melhorada.
- Testes automatizados das regras criticas e das migracoes.
- Procedimento de backup/restore.

## Should

- Pagamento combinado no PDV.
- Troca/devolucao com regra clara e reflexo em estoque/caixa.
- Contas a pagar/receber simples.
- Relatorio de fluxo de caixa diario consolidado.
- Paginacao e busca nas listas grandes.
- Controle minimo de lote/validade por entrada.

## Could

- Integracao com ReceitaWS.
- Cupom simples em PDF/TXT.
- Dashboard com ranking por categoria e curva ABC.
- Sincronizacao em rede local com mais de um terminal.
