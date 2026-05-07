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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class DesktopReturnService {
    private final Connection con;

    public DesktopReturnService(Connection con) {
        this.con = con;
    }

    public List<ReturnableItem> listReturnableItems(long vendaId) throws Exception {
        Map<String, Object> venda = one("select id, status from vendas where id = ?", vendaId);
        if (venda == null) {
            throw new AppException("Venda nao encontrada.");
        }
        if (!"CONCLUIDA".equals(venda.get("status"))) {
            throw new AppException("Somente vendas concluidas podem ser devolvidas.");
        }

        List<ReturnableItem> items = new ArrayList<>();
        for (Map<String, Object> row : rows("""
                select vi.id as venda_item_id,
                       vi.produto_id,
                       p.nome,
                       vi.quantidade as quantidade_vendida,
                       vi.preco_unitario,
                       coalesce((
                           select sum(di.quantidade)
                           from devolucao_itens di
                           join devolucoes d on d.id = di.devolucao_id
                           where di.venda_item_id = vi.id
                       ), 0) as quantidade_devolvida
                from venda_itens vi
                join produtos p on p.id = vi.produto_id
                where vi.venda_id = ?
                order by vi.id
                """, vendaId)) {
            BigDecimal vendida = money(row.get("quantidade_vendida"));
            BigDecimal devolvida = money(row.get("quantidade_devolvida"));
            BigDecimal disponivel = vendida.subtract(devolvida).setScale(2, RoundingMode.HALF_UP);
            if (disponivel.compareTo(BigDecimal.ZERO) > 0) {
                items.add(new ReturnableItem(
                        longValue(row.get("venda_item_id")),
                        longValue(row.get("produto_id")),
                        row.get("nome").toString(),
                        vendida.setScale(2, RoundingMode.HALF_UP),
                        devolvida.setScale(2, RoundingMode.HALF_UP),
                        disponivel,
                        money(row.get("preco_unitario")).setScale(2, RoundingMode.HALF_UP)
                ));
            }
        }
        return items;
    }

    public ReturnResult processReturn(ReturnRequest request, long operatorId) throws Exception {
        BusinessRules.requireNotBlank(request.tipo(), "Tipo da operacao");
        BusinessRules.requireNotBlank(request.formaDestino(), "Destino financeiro");
        BusinessRules.requireNotBlank(request.motivo(), "Motivo");
        if (request.itens() == null || request.itens().isEmpty()) {
            throw new AppException("Selecione ao menos um item para devolucao.");
        }

        String tipo = request.tipo().toUpperCase(Locale.ROOT);
        String formaDestino = request.formaDestino().toUpperCase(Locale.ROOT);
        if (!List.of("DEVOLUCAO", "TROCA").contains(tipo)) {
            throw new AppException("Tipo de operacao invalido.");
        }

        return withTransaction(() -> {
            Map<String, Object> venda = one("select caixa_id, cliente_id, status from vendas where id = ?", request.vendaId());
            if (venda == null) {
                throw new AppException("Venda nao encontrada.");
            }
            if (!"CONCLUIDA".equals(venda.get("status"))) {
                throw new AppException("A venda precisa estar concluida para processar devolucao.");
            }

            Map<Long, ReturnableItem> available = new LinkedHashMap<>();
            for (ReturnableItem item : listReturnableItems(request.vendaId())) {
                available.put(item.vendaItemId(), item);
            }

            BigDecimal total = BigDecimal.ZERO;
            for (ReturnItemRequest item : request.itens()) {
                BusinessRules.requirePositive(item.quantidade(), "Quantidade devolvida");
                ReturnableItem original = available.get(item.vendaItemId());
                if (original == null) {
                    throw new AppException("Um dos itens nao pode mais ser devolvido.");
                }
                if (item.quantidade().compareTo(original.quantidadeDisponivel()) > 0) {
                    throw new AppException("Quantidade devolvida maior que o disponivel para " + original.nome() + ".");
                }
                total = total.add(original.precoUnitario().multiply(item.quantidade()));
            }
            total = total.setScale(2, RoundingMode.HALF_UP);
            BusinessRules.requirePositive(total, "Valor total da devolucao");

            Long clienteId = venda.get("cliente_id") == null ? null : longValue(venda.get("cliente_id"));
            String formaFinal = "TROCA".equals(tipo) ? "VALE_TROCA" : formaDestino;

            if ("ABATER_FIADO".equals(formaFinal)) {
                abaterFiado(request.vendaId(), total);
            }

            long devolucaoId = insert("""
                    insert into devolucoes (venda_id, caixa_id, operador_id, cliente_id, tipo, forma_destino, valor_total, motivo, criado_em)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, request.vendaId(), request.caixaId(), operatorId, clienteId, tipo, formaFinal, total, request.motivo(), now());

            for (ReturnItemRequest item : request.itens()) {
                ReturnableItem original = available.get(item.vendaItemId());
                insert("""
                        insert into devolucao_itens (devolucao_id, venda_item_id, produto_id, quantidade, valor_unitario)
                        values (?, ?, ?, ?, ?)
                        """, devolucaoId, item.vendaItemId(), original.produtoId(), item.quantidade(), original.precoUnitario());
                update("update produtos set estoque_atual = estoque_atual + ? where id = ?", item.quantidade(), original.produtoId());
                update("""
                        insert into movimentacao_estoque (produto_id, tipo, quantidade, referencia_id, operador_id, timestamp, observacao)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """, original.produtoId(), tipo, item.quantidade(), devolucaoId, operatorId, now(),
                        request.motivo() + " | venda #" + request.vendaId());
            }

            String codigoCredito = null;
            if ("VALE_TROCA".equals(formaFinal)) {
                codigoCredito = generateCreditCode();
                insert("""
                        insert into creditos_troca (codigo, cliente_id, devolucao_id, saldo, status, criado_em, operador_id)
                        values (?, ?, ?, ?, 'ABERTO', ?, ?)
                        """, codigoCredito, clienteId, devolucaoId, total, now(), operatorId);
            } else if (!"ABATER_FIADO".equals(formaFinal)) {
                update("""
                        insert into caixa_operacoes (caixa_id, tipo, valor, motivo, operador_id, timestamp)
                        values (?, ?, ?, ?, ?, ?)
                        """, request.caixaId(), "DEVOLUCAO_" + formaFinal, total,
                        request.motivo() + " | venda #" + request.vendaId(), operatorId, now());
            }

            return new ReturnResult(devolucaoId, total, formaFinal, codigoCredito);
        });
    }

    public void consumeStoreCredit(String codigo, BigDecimal valor, Long clienteId) throws Exception {
        BusinessRules.requireNotBlank(codigo, "Codigo do vale troca");
        BusinessRules.requirePositive(valor, "Valor do vale troca");

        Map<String, Object> credito = one("""
                select id, cliente_id, saldo, status
                from creditos_troca
                where codigo = ?
                """, codigo.trim().toUpperCase(Locale.ROOT));
        if (credito == null) {
            throw new AppException("Vale troca nao encontrado.");
        }
        if ("UTILIZADO".equals(credito.get("status"))) {
            throw new AppException("Vale troca ja utilizado.");
        }
        Long clienteCredito = credito.get("cliente_id") == null ? null : longValue(credito.get("cliente_id"));
        if (clienteCredito != null && !clienteCredito.equals(clienteId)) {
            throw new AppException("Selecione o cliente correto para usar este vale troca.");
        }
        BigDecimal saldo = money(credito.get("saldo"));
        if (saldo.compareTo(valor) < 0) {
            throw new AppException("Saldo insuficiente no vale troca.");
        }
        BigDecimal novoSaldo = saldo.subtract(valor).setScale(2, RoundingMode.HALF_UP);
        update("""
                update creditos_troca
                set saldo = ?, status = ?, utilizado_em = ?
                where id = ?
                """, novoSaldo, novoSaldo.compareTo(BigDecimal.ZERO) == 0 ? "UTILIZADO" : "PARCIAL", now(), longValue(credito.get("id")));
    }

    private void abaterFiado(long vendaId, BigDecimal valor) throws Exception {
        Map<String, Object> fiado = one("""
                select id, valor, valor_pago, status
                from fiado
                where venda_id = ? and status = 'ABERTO'
                order by id desc
                limit 1
                """, vendaId);
        if (fiado == null) {
            throw new AppException("Nao existe fiado aberto para esta venda.");
        }
        BigDecimal valorAtual = money(fiado.get("valor"));
        BigDecimal valorPago = money(fiado.get("valor_pago"));
        BigDecimal aberto = valorAtual.subtract(valorPago).setScale(2, RoundingMode.HALF_UP);
        if (aberto.compareTo(valor) < 0) {
            throw new AppException("O valor da devolucao excede o fiado em aberto da venda.");
        }
        BigDecimal novoValor = valorAtual.subtract(valor).setScale(2, RoundingMode.HALF_UP);
        String novoStatus = novoValor.compareTo(valorPago) <= 0 ? "PAGO" : "ABERTO";
        update("update fiado set valor = ?, status = ? where id = ?", novoValor, novoStatus, longValue(fiado.get("id")));
    }

    private String generateCreditCode() {
        return "VT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String now() {
        return LocalDateTime.now().toString();
    }

    private long insert(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private void update(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        }
    }

    private void bind(PreparedStatement ps, Object... args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    private BigDecimal money(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.toString());
    }

    private long longValue(Object value) {
        return ((Number) value).longValue();
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

    public record ReturnableItem(
            long vendaItemId,
            long produtoId,
            String nome,
            BigDecimal quantidadeVendida,
            BigDecimal quantidadeDevolvida,
            BigDecimal quantidadeDisponivel,
            BigDecimal precoUnitario
    ) {}

    public record ReturnItemRequest(long vendaItemId, BigDecimal quantidade) {}

    public record ReturnRequest(
            long vendaId,
            long caixaId,
            String tipo,
            String formaDestino,
            String motivo,
            List<ReturnItemRequest> itens
    ) {}

    public record ReturnResult(
            long devolucaoId,
            BigDecimal valorTotal,
            String formaDestino,
            String codigoCredito
    ) {}
}
