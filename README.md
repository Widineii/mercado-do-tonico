# Mercado do Tonico

Sistema desktop de gestao para pequeno supermercado, feito em Java Swing com banco SQLite local.

## O que esta implementado

- Aplicativo desktop com janela normal do Windows.
- Login com usuarios `ADMIN`, `GERENTE`, `CAIXA` e `ESTOQUE`.
- Senhas e PIN de gerente gravados com bcrypt.
- Dashboard com alertas de estoque baixo, validade proxima e fiado em aberto.
- Dois caixas independentes com bloqueio por operador no banco.
- Abertura, venda, desconto com PIN de gerente, sangria, suprimento, cancelamento da ultima venda e fechamento.
- Pagamento combinado no PDV com validacao de soma exata.
- Bloqueio de estoque insuficiente tambem no fluxo web opcional.
- Cadastro e ajuste de estoque com log de movimentacao.
- Cadastro de fornecedores com busca local por CNPJ e integracao opcional com ReceitaWS.
- Importacao de XML de NF-e com previa e entrada automatica de estoque.
- Cadastro de clientes e controle de fiado com pagamentos parciais.
- Relatorios de vendas, mais vendidos, lucro, estoque baixo e fiado em aberto.
- Migrations versionadas em `src/main/resources/db/migration`.
- Seed com caixas, categorias, fornecedor e 10 produtos.

## Requisitos

- Java 17 ou superior.
- Maven 3.9 ou superior.

## Como abrir o sistema desktop

De dois cliques no arquivo:

```text
ABRIR_MERCADO_TONICO_DESKTOP.bat
```

Para operacao em rede com 2 caixas:

```text
ABRIR_SERVIDOR_CAIXA.bat   (PC servidor)
ABRIR_CAIXA_CLIENTE.bat    (PC caixa cliente)
```

Consulte `docs/REDE_2_CAIXAS.md`.

Ou rode pelo terminal:

```powershell
.\mvnw.cmd -q -DskipTests package
java --enable-native-access=ALL-UNNAMED -jar target\mercado-do-tonico-1.0.0.jar
```

No primeiro login dos usuarios padrao, o sistema exige troca de senha.

## Versao web local opcional

Tambem existe uma interface web local criada no comeco do projeto. Ela nao e o foco principal agora, mas ficou alinhada com as permissoes por perfil e com pagamento combinado.

Para rodar a versao web opcional:

```powershell
mvn spring-boot:run
```

Depois acesse:

```text
http://localhost:8080
```

O banco SQLite sera criado automaticamente em:

```text
data/mercado-tonico.db
```

## Acessos iniciais

Administrador:

```text
login: admin
senha: admin123
PIN gerente: 1234
```

Gerente:

```text
login: gerente
senha: gerente123
PIN gerente: 4321
```

Caixas:

```text
login: caixa1
senha: caixa123

login: caixa2
senha: caixa123
```

Estoque:

```text
login: estoque1
senha: estoque123
```

## Observacoes operacionais

- Para leitor de codigo de barras, use a tela `Caixa`: o campo de codigo fica em foco e funciona como entrada rapida de teclado.
- Desconto, sangria e cancelamento exigem PIN de gerente.
- Fiado so deve ser finalizado com cliente selecionado.
- A importacao XML aceita NF-e padrao SEFAZ e le tags como `CNPJ`, `xNome`, `nNF`, `vNF`, `det/prod/xProd`, `cEAN`, `qCom` e `vUnCom`.
- A busca por CNPJ na tela de fornecedores consulta primeiro o cadastro local e, se habilitado, pode usar ReceitaWS com:
  - `app.receitaws.enabled=true`
  - `app.receitaws.timeout-seconds=5`

## Estrutura principal

```text
src/main/java/br/com/mercadotonico
  desktop      aplicativo Swing executavel
  config       configuracao, autenticacao e inicializacao do banco
  controller   rotas web
  service      regras de caixa, estoque, vendas, fiado, XML e relatorios
src/main/resources/templates
  telas Thymeleaf em pt-BR
src/main/resources/db/migration
  SQL da estrutura e seed
```

## Validacao rapida

```powershell
.\mvnw.cmd test
```

Ou rode validacao automatizada completa:

```text
VALIDAR_SISTEMA.bat
```

## Operacao rapida (15 minutos)

1. Abrir o sistema e trocar as senhas temporarias no primeiro acesso.
2. Logar com `admin`, `gerente`, `caixa1` e `estoque1` para validar permissoes.
3. Realizar uma venda simples e uma venda com pagamento combinado.
4. Importar um XML real de NF-e e conferir atualizacao de estoque.
5. Executar `BACKUP_MERCADO_TONICO.bat`.
6. Executar `RESTORE_MERCADO_TONICO.bat` e validar reabertura.
