# Checklist de homologacao

## Cadastro e seguranca

- Entrar com `admin`, `gerente`, `caixa1` e `estoque1`.
- Confirmar que `CAIXA` nao acessa telas de estoque e relatorios.
- Confirmar que `ESTOQUE` nao acessa PDV.
- Confirmar que desconto acima do limite do perfil falha sem PIN gerencial.

## Estoque

- Cadastrar produto sem codigo de barras e validar codigo interno gerado.
- Fazer ajuste de entrada e saida com motivo.
- Tentar retirar estoque acima do saldo e validar bloqueio.
- Importar XML e validar aumento de estoque e atualizacao de custo medio.

## PDV

- Abrir caixa 1 com `caixa1`.
- Tentar abrir o mesmo caixa com outro usuario e validar bloqueio.
- Vender item com desconto dentro do limite do perfil.
- Tentar vender item com preco zero sem autorizacao e validar bloqueio.
- Fechar caixa e conferir valor esperado x contado.

## Fiado e relatorios

- Cadastrar cliente, fazer venda em fiado e registrar pagamento parcial.
- Conferir fiado em aberto no painel.
- Conferir relatorio de vendas e produtos mais vendidos.

## Suporte

- Executar backup.
- Validar existencia de `data\logs\app.log`.
