# Checklist de homologacao final

## Perfis

- [ ] `admin` acessa todos os modulos.
- [ ] `gerente` acessa PDV, estoque, fiado, financeiro e relatorios.
- [ ] `caixa1` acessa apenas PDV, fiado operacional e painel simples.
- [ ] `estoque1` acessa estoque, fornecedores e XML, sem PDV.

## Fluxo principal

- [ ] Abrir caixa com fundo inicial.
- [ ] Registrar venda simples.
- [ ] Registrar venda com pagamento combinado.
- [ ] Registrar venda com fiado.
- [ ] Gerar comprovante TXT e PDF.
- [ ] Reemitir comprovante.
- [ ] Registrar troca ou devolucao.
- [ ] Usar vale troca em nova venda.
- [ ] Fechar caixa com valor contado.

## Estoque

- [ ] Cadastrar produto novo.
- [ ] Registrar entrada com lote e validade.
- [ ] Ver lote e validade na tela de estoque.
- [ ] Validar alerta de produto abaixo do minimo.
- [ ] Validar produto proximo ao vencimento.
- [ ] Fazer ajuste por inventario.
- [ ] Registrar perda/quebra.

## Financeiro

- [ ] Criar conta a pagar.
- [ ] Criar conta a receber.
- [ ] Baixar lancamento parcialmente.
- [ ] Baixar lancamento totalmente.
- [ ] Ver pendencias por vencimento.
- [ ] Ver reflexo no fechamento diario.

## Relatorios

- [ ] Fechamento diario consolidado.
- [ ] Fluxo financeiro do dia.
- [ ] Devolucoes do dia.
- [ ] Comprovantes gerados.
- [ ] Validades proximas por lote.

## Operacao do PDV

- [ ] `F1` foca o campo de codigo.
- [ ] `F4` finaliza venda.
- [ ] `F6` limpa carrinho.
- [ ] `F7` abre suprimento.
- [ ] `F8` abre sangria.
- [ ] `F9` abre troca/devolucao.
- [ ] `F10` reemite comprovante.
- [ ] `ESC` limpa o codigo.
- [ ] Indicador visual de pagamento responde corretamente.

## Logs e suporte

- [ ] Arquivo `data/logs/app.log` existe.
- [ ] Erros operacionais gravam usuario e stack trace.
- [ ] Backup manual gera copia do banco.
- [ ] Restore por `RESTORE_MERCADO_TONICO.bat` concluido com sucesso.

## Go-live

- [ ] Build final gerado sem falhas.
- [ ] Banco inicializado com migrations aplicadas.
- [ ] Dados de acesso entregues ao cliente.
- [ ] Senhas temporarias trocadas por senhas definitivas.
- [ ] Operador treinado no fluxo basico.
- [ ] Plano de rollback conhecido.
