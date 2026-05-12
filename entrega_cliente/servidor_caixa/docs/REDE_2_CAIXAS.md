# Rede local com 2 caixas

## Objetivo

- PC 1: servidor + caixa principal.
- PC 2: caixa cliente.
- Ambos usam o mesmo banco.

## 1) Preparar PC servidor (PC 1)

1. Abra o sistema por `ABRIR_SERVIDOR_CAIXA.bat` para usar o banco local padrao.
2. Compartilhe na rede a pasta `data` com permissao de leitura e gravacao.
3. Anote o caminho de rede, exemplo:
   - `\\SERVIDOR\mercado\data\mercado-tunico.db`

## 2) Preparar PC cliente (PC 2)

1. Copie o projeto para o PC 2.
2. Edite `config/desktop.properties`.
3. Preencha:

```properties
mercado.db.url=jdbc:sqlite:\\\\SERVIDOR\mercado\data\mercado-tunico.db
```

4. Abra por `ABRIR_CAIXA_CLIENTE.bat`.

## 3) Uso recomendado

- Sempre abrir primeiro o servidor e depois o cliente.
- Evitar desligar o servidor com os caixas abertos.
- Fazer backup diario no servidor com `BACKUP_MERCADO_TUNICO.bat`.

## Observacao tecnica

SQLite em rede local funciona para operacao pequena, mas se o volume aumentar (muitos operadores simultaneos), o ideal e migrar para PostgreSQL para concorrencia mais robusta.
