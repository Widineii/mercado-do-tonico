package br.com.mercadotonico.desktop;

import br.com.mercadotonico.core.AppException;
import br.com.mercadotonico.core.BusinessRules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DesktopInventoryService {
    private final Connection con;

    public DesktopInventoryService(Connection con) {
        this.con = con;
    }

    /**
     * Atualiza cadastro completo do produto (exceto estoque atual e codigo interno).
     * Registra historico de preco quando custo ou venda mudam.
     */
    public void updateProduct(long produtoId, ProductUpdate u, long operatorId) throws Exception {
        BusinessRules.requireNotBlank(u.nome(), "Nome do produto");
        BusinessRules.requireNotBlank(u.unidade(), "Unidade");
        BusinessRules.requireNonNegative(u.precoCusto(), "Preco de custo");
        BusinessRules.requireNonNegative(u.precoVenda(), "Preco de venda");
        BusinessRules.requireNonNegative(u.estoqueMinimo(), "Estoque minimo");

        Map<String, Object> cur = one("""
                select preco_custo, preco_venda, codigo_barras, sku
                from produtos where id = ?
                """, produtoId);
        if (cur == null) {
            throw new AppException("Produto nao encontrado.");
        }
        String barrasNorm = u.codigoBarras() == null ? "" : u.codigoBarras().trim();
        if (!barrasNorm.isEmpty()) {
            Map<String, Object> dup = one("select id from produtos where codigo_barras = ? and id <> ?", barrasNorm, produtoId);
            if (dup != null) {
                throw new AppException("Ja existe outro produto com este codigo de barras.");
            }
        }
        String skuNorm = u.sku() == null ? "" : u.sku().trim();
        if (!skuNorm.isEmpty()) {
            Map<String, Object> dup = one("select id from produtos where sku = ? and id <> ?", skuNorm, produtoId);
            if (dup != null) {
                throw new AppException("Ja existe outro produto com este SKU.");
            }
        }

        BigDecimal oldCusto = money(cur.get("preco_custo"));
        BigDecimal oldVenda = money(cur.get("preco_venda"));

        withTransaction(() -> {
            update("""
                    update produtos set
                        nome = ?,
                        codigo_barras = ?,
                        sku = ?,
                        categoria = ?,
                        unidade = ?,
                        marca = ?,
                        fabricante = ?,
                        preco_custo = ?,
                        preco_venda = ?,
                        estoque_minimo = ?,
                        localizacao = ?,
                        validade = ?,
                        lote_padrao = ?,
                        observacoes = ?,
                        imagem_url = ?,
                        ncm = ?,
                        cest = ?,
                        fornecedor_id = ?,
                        ativo = ?,
                        controla_lote = ?,
                        permite_preco_zero = ?
                    where id = ?
                    """,
                    u.nome(),
                    blankToNull(u.codigoBarras()),
                    blankToNull(u.sku()),
                    blankToNull(u.categoria()),
                    u.unidade(),
                    blankToNull(u.marca()),
                    blankToNull(u.fabricante()),
                    u.precoCusto(),
                    u.precoVenda(),
                    u.estoqueMinimo(),
                    blankToNull(u.localizacao()),
                    blankToNull(u.validade()),
                    blankToNull(u.lotePadrao()),
                    blankToNull(u.observacoes()),
                    blankToNull(u.imagemUrl()),
                    blankToNull(u.ncm()),
                    blankToNull(u.cest()),
                    u.fornecedorId(),
                    u.ativo() ? 1 : 0,
                    u.controlaLote() ? 1 : 0,
                    u.permitePrecoZero() ? 1 : 0,
                    produtoId);
            if (oldCusto.compareTo(u.precoCusto()) != 0 || oldVenda.compareTo(u.precoVenda()) != 0) {
                recordPriceHistory(produtoId, oldCusto, u.precoCusto(), oldVenda, u.precoVenda(), operatorId, "Edicao cadastro de produto");
            }
            return null;
        });
    }

    public long saveProduct(ProductDraft draft, long operatorId, String codigoInterno) throws Exception {
        BusinessRules.requireNotBlank(draft.nome(), "Nome do produto");
        BusinessRules.requireNotBlank(draft.unidade(), "Unidade");
        BusinessRules.requireNonNegative(draft.precoCusto(), "Preco de custo");
        BusinessRules.requireNonNegative(draft.precoVenda(), "Preco de venda");
        BusinessRules.requireNonNegative(draft.estoqueInicial(), "Estoque");
        BusinessRules.requireNonNegative(draft.estoqueMinimo(), "Estoque minimo");

        long produtoId = insert("""
            insert into produtos (nome, codigo_barras, sku, codigo_interno, categoria, unidade, preco_custo, preco_venda, estoque_atual, estoque_minimo, localizacao, validade, ativo, observacoes)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
            """, draft.nome(), blankToNull(draft.codigoBarras()), blankToNull(draft.sku()), codigoInterno,
                blankToNull(draft.categoria()), draft.unidade(), draft.precoCusto(), draft.precoVenda(),
                draft.estoqueInicial(), draft.estoqueMinimo(), blankToNull(draft.localizacao()), blankToNull(draft.validade()),
                blankToNull(draft.observacoes()));
        recordPriceHistory(produtoId, null, draft.precoCusto(), null, draft.precoVenda(), operatorId, "Cadastro inicial do produto");
        return produtoId;
    }

    public void registerStockEntry(StockEntryRequest request, long operatorId) throws Exception {
        BusinessRules.requirePositive(request.quantidade(), "Quantidade de entrada");
        BusinessRules.requireNonNegative(request.custoUnitario(), "Custo unitario");
        BusinessRules.requireNotBlank(request.observacao(), "Observacao");

        withTransaction(() -> {
            Map<String, Object> produto = one("select nome, estoque_atual, preco_custo, preco_venda, controla_lote from produtos where id = ?", request.produtoId());
            if (produto == null) {
                throw new AppException("Produto nao encontrado para entrada de mercadoria.");
            }
            BigDecimal estoqueAtual = money(produto.get("estoque_atual"));
            BigDecimal custoAtual = money(produto.get("preco_custo"));
            BigDecimal custoMedio = weightedAverageCost(estoqueAtual, custoAtual, request.quantidade(), request.custoUnitario());
            String validadeProduto = nextExpiry(produtoId(request), request.validade());

            insert("""
                insert into entradas_estoque (produto_id, quantidade, custo_unitario, lote, validade, documento, observacao, operador_id, criado_em)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, request.produtoId(), request.quantidade(), request.custoUnitario(), blankToNull(request.lote()),
                    blankToNull(request.validade()), blankToNull(request.documento()), request.observacao(), operatorId, now());

            update("""
                update produtos
                set estoque_atual = estoque_atual + ?, preco_custo = ?, lote_padrao = coalesce(?, lote_padrao), validade = ?, controla_lote = ?
                where id = ?
                """, request.quantidade(), custoMedio, blankToNull(request.lote()), blankToNull(validadeProduto),
                    hasLotData(request) ? 1 : intValue(produto.get("controla_lote")), request.produtoId());

            update("""
                insert into movimentacao_estoque (produto_id, tipo, quantidade, operador_id, timestamp, observacao)
                values (?, 'ENTRADA', ?, ?, ?, ?)
                """, request.produtoId(), request.quantidade(), operatorId, now(), request.observacao());

            recordPriceHistory(request.produtoId(), custoAtual, custoMedio, money(produto.get("preco_venda")), money(produto.get("preco_venda")),
                    operatorId, "Entrada manual de mercadoria");
            return null;
        });
    }

    public void reconcileInventory(InventoryCountRequest request, long operatorId) throws Exception {
        BusinessRules.requireNonNegative(request.saldoContado(), "Saldo contado");
        BusinessRules.requireNotBlank(request.motivo(), "Motivo do inventario");

        withTransaction(() -> {
            Map<String, Object> produto = one("select nome, estoque_atual from produtos where id = ?", request.produtoId());
            if (produto == null) {
                throw new AppException("Produto nao encontrado para inventario.");
            }
            BigDecimal saldoSistema = money(produto.get("estoque_atual"));
            BigDecimal diferenca = request.saldoContado().subtract(saldoSistema);

            insert("""
                insert into inventario_ajustes (produto_id, saldo_sistema, saldo_contado, diferenca, motivo, operador_id, criado_em)
                values (?, ?, ?, ?, ?, ?, ?)
                """, request.produtoId(), saldoSistema, request.saldoContado(), diferenca, request.motivo(), operatorId, now());

            update("update produtos set estoque_atual = ? where id = ?", request.saldoContado(), request.produtoId());
            update("""
                insert into movimentacao_estoque (produto_id, tipo, quantidade, operador_id, timestamp, observacao)
                values (?, 'INVENTARIO_AJUSTE', ?, ?, ?, ?)
                """, request.produtoId(), diferenca.abs(), operatorId, now(),
                    request.motivo() + " | saldo sistema: " + saldoSistema + " | saldo contado: " + request.saldoContado());
            return null;
        });
    }

    public void registerMovement(long produtoId, String tipo, BigDecimal quantidade, String motivo, long operatorId) throws Exception {
        BusinessRules.requirePositive(quantidade, "Quantidade");
        BusinessRules.requireNotBlank(tipo, "Tipo");
        BusinessRules.requireNotBlank(motivo, "Motivo");

        String upperType = tipo.toUpperCase();
        withTransaction(() -> {
            Map<String, Object> produto = one("select nome, estoque_atual from produtos where id = ?", produtoId);
            if (produto == null) {
                throw new AppException("Produto nao encontrado.");
            }
            BigDecimal estoqueAtual = money(produto.get("estoque_atual"));
            if (List.of("SAIDA", "PERDA", "QUEBRA").contains(upperType)) {
                BusinessRules.ensureStockAvailable(estoqueAtual, quantidade, produto.get("nome").toString());
                int changed = updateCount("update produtos set estoque_atual = estoque_atual - ? where id = ? and estoque_atual >= ?",
                        quantidade, produtoId, quantidade);
                if (changed == 0) {
                    throw new AppException("Nao foi possivel concluir a baixa de estoque.");
                }
            } else {
                update("update produtos set estoque_atual = estoque_atual + ? where id = ?", quantidade, produtoId);
            }
            update("""
                insert into movimentacao_estoque (produto_id, tipo, quantidade, operador_id, timestamp, observacao)
                values (?, ?, ?, ?, ?, ?)
                """, produtoId, upperType, quantidade, operatorId, now(), motivo);
            return null;
        });
    }

    public StockLossResult registerStockLoss(StockLossRequest request, long operatorId) throws Exception {
        BusinessRules.requirePositive(request.quantidade(), "Quantidade");
        BusinessRules.requireNotBlank(request.categoria(), "Motivo da baixa");
        BusinessRules.requireNotBlank(request.observacao(), "Observacao");

        String categoria = request.categoria().trim().toUpperCase(Locale.ROOT);
        String movimentoTipo = "AVARIA_QUEBRA".equals(categoria) ? "QUEBRA" : "PERDA";
        return withTransaction(() -> {
            Map<String, Object> produto = one("""
                    select nome, estoque_atual, preco_custo
                    from produtos
                    where id = ?
                    """, request.produtoId());
            if (produto == null) {
                throw new AppException("Produto nao encontrado.");
            }

            String produtoNome = produto.get("nome").toString();
            BigDecimal estoqueAtual = money(produto.get("estoque_atual"));
            BigDecimal custoUnitario = money(produto.get("preco_custo")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal valorPerda = custoUnitario.multiply(request.quantidade()).setScale(2, RoundingMode.HALF_UP);

            BusinessRules.ensureStockAvailable(estoqueAtual, request.quantidade(), produtoNome);
            int changed = updateCount("""
                    update produtos
                    set estoque_atual = estoque_atual - ?
                    where id = ? and estoque_atual >= ?
                    """, request.quantidade(), request.produtoId(), request.quantidade());
            if (changed == 0) {
                throw new AppException("Nao foi possivel concluir a baixa de estoque.");
            }

            String obsEstoque = categoryLabel(categoria) + " | " + request.observacao();
            insert("""
                    insert into movimentacao_estoque (produto_id, tipo, quantidade, operador_id, timestamp, observacao)
                    values (?, ?, ?, ?, ?, ?)
                    """, request.produtoId(), movimentoTipo, request.quantidade(), operatorId, now(), obsEstoque);

            long lancamentoId = insert("""
                    insert into financeiro_lancamentos
                    (tipo, descricao, parceiro, categoria, valor_total, valor_baixado, vencimento, status, forma_baixa, observacao, criado_por, criado_em, baixado_em)
                    values ('PAGAR', ?, ?, ?, ?, ?, ?, 'QUITADO', 'BAIXA_ESTOQUE', ?, ?, ?, ?)
                    """,
                    "Perda de estoque - " + produtoNome,
                    "Estoque",
                    "Perdas de estoque - " + categoryLabel(categoria),
                    valorPerda,
                    valorPerda,
                    LocalDate.now().toString(),
                    "Produto #" + request.produtoId()
                            + " | qtd " + request.quantidade()
                            + " | custo unitario " + custoUnitario
                            + " | " + request.observacao(),
                    operatorId,
                    now(),
                    now());

            return new StockLossResult(produtoNome, movimentoTipo, categoria, valorPerda, lancamentoId);
        });
    }

    private String categoryLabel(String categoria) {
        return switch (categoria) {
            case "VENCIMENTO" -> "Vencimento";
            case "AVARIA_QUEBRA" -> "Avaria/quebra";
            case "USO_INTERNO" -> "Degustacao/uso interno";
            case "FURTO" -> "Furto";
            default -> categoria;
        };
    }

    public BigDecimal weightedAverageCost(BigDecimal estoqueAtual, BigDecimal custoAtual, BigDecimal entrada, BigDecimal custoEntrada) {
        if (estoqueAtual.compareTo(BigDecimal.ZERO) <= 0) {
            return custoEntrada.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal totalQtd = estoqueAtual.add(entrada);
        return estoqueAtual.multiply(custoAtual)
                .add(entrada.multiply(custoEntrada))
                .divide(totalQtd, 2, RoundingMode.HALF_UP);
    }

    private void recordPriceHistory(long produtoId, BigDecimal custoAnterior, BigDecimal custoNovo,
                                    BigDecimal vendaAnterior, BigDecimal vendaNovo, long operatorId, String motivo) throws Exception {
        update("""
            insert into historico_preco
            (produto_id, preco_custo_anterior, preco_custo_novo, preco_venda_anterior, preco_venda_novo, alterado_por, motivo, timestamp)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """, produtoId, custoAnterior, custoNovo, vendaAnterior, vendaNovo, operatorId, motivo, now());
    }

    private Object blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        return ((Number) value).intValue();
    }

    private boolean hasLotData(StockEntryRequest request) {
        return request.lote() != null && !request.lote().isBlank()
                || request.validade() != null && !request.validade().isBlank();
    }

    private long produtoId(StockEntryRequest request) {
        return request.produtoId();
    }

    private String nextExpiry(long produtoId, String candidateValidity) throws Exception {
        LocalDate best = parseDate(candidateValidity);
        for (Map<String, Object> row : rows("""
                select validade
                from entradas_estoque
                where produto_id = ? and validade is not null and trim(validade) <> ''
                """, produtoId)) {
            LocalDate current = parseDate(row.get("validade") == null ? null : row.get("validade").toString());
            if (current != null && (best == null || current.isBefore(best))) {
                best = current;
            }
        }
        return best == null ? null : best.toString();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 10) {
            normalized = normalized.substring(0, 10);
        }
        return LocalDate.parse(normalized);
    }

    private String now() {
        return LocalDateTime.now().toString();
    }

    private BigDecimal money(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.toString());
    }

    private void update(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        }
    }

    private int updateCount(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    private long insert(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private void bind(PreparedStatement ps, Object... args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    private Map<String, Object> one(String sql, Object... args) throws Exception {
        List<Map<String, Object>> list = rows(sql, args);
        return list.isEmpty() ? null : list.get(0);
    }

    private List<Map<String, Object>> rows(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            ResultSet rs = ps.executeQuery();
            ResultSetMetaData md = rs.getMetaData();
            List<Map<String, Object>> list = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnLabel(i), rs.getObject(i));
                }
                list.add(row);
            }
            return list;
        }
    }

    private <T> T withTransaction(SqlSupplier<T> work) throws Exception {
        boolean autoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            T result = work.get();
            con.commit();
            return result;
        } catch (Exception e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(autoCommit);
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }

    public record ProductDraft(
            String nome,
            String codigoBarras,
            String sku,
            String categoria,
            String unidade,
            BigDecimal precoCusto,
            BigDecimal precoVenda,
            BigDecimal estoqueInicial,
            BigDecimal estoqueMinimo,
            String localizacao,
            String validade,
            String observacoes
    ) {}

    public record ProductUpdate(
            String nome,
            String codigoBarras,
            String sku,
            String categoria,
            String unidade,
            String marca,
            String fabricante,
            BigDecimal precoCusto,
            BigDecimal precoVenda,
            BigDecimal estoqueMinimo,
            String localizacao,
            String validade,
            String lotePadrao,
            String observacoes,
            String imagemUrl,
            String ncm,
            String cest,
            Long fornecedorId,
            boolean ativo,
            boolean controlaLote,
            boolean permitePrecoZero
    ) {}

    public record StockEntryRequest(
            long produtoId,
            BigDecimal quantidade,
            BigDecimal custoUnitario,
            String lote,
            String validade,
            String documento,
            String observacao
    ) {}

    public record InventoryCountRequest(
            long produtoId,
            BigDecimal saldoContado,
            String motivo
    ) {}

    public record StockLossRequest(
            long produtoId,
            BigDecimal quantidade,
            String categoria,
            String observacao
    ) {}

    public record StockLossResult(
            String produtoNome,
            String movimentoTipo,
            String categoria,
            BigDecimal valorPerda,
            long lancamentoFinanceiroId
    ) {}
}
