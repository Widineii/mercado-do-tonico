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

public class DesktopFinanceService {
    private final Connection con;

    public DesktopFinanceService(Connection con) {
        this.con = con;
    }

    public long createEntry(FinanceEntryRequest request, long operatorId) throws Exception {
        BusinessRules.requireNotBlank(request.tipo(), "Tipo");
        BusinessRules.requireNotBlank(request.descricao(), "Descricao");
        BusinessRules.requireNotBlank(request.vencimento(), "Vencimento");
        BusinessRules.requirePositive(request.valorTotal(), "Valor total");

        String tipo = request.tipo().toUpperCase(Locale.ROOT);
        if (!List.of("PAGAR", "RECEBER").contains(tipo)) {
            throw new AppException("Tipo financeiro invalido.");
        }

        return insert("""
                insert into financeiro_lancamentos
                (tipo, descricao, parceiro, categoria, valor_total, valor_baixado, vencimento, status, observacao, criado_por, criado_em, nota_fiscal_id)
                values (?, ?, ?, ?, ?, 0, ?, 'ABERTO', ?, ?, ?, ?)
                """, tipo, request.descricao(), blankToNull(request.parceiro()), blankToNull(request.categoria()),
                scaled(request.valorTotal()), request.vencimento(), blankToNull(request.observacao()), operatorId, now(),
                request.notaFiscalId());
    }

    public void settle(long lancamentoId, BigDecimal valor, String formaBaixa, String observacao) throws Exception {
        BusinessRules.requirePositive(valor, "Valor da baixa");
        BusinessRules.requireNotBlank(formaBaixa, "Forma da baixa");

        withTransaction(() -> {
            Map<String, Object> entry = one("""
                    select id, tipo, valor_total, valor_baixado, status
                    from financeiro_lancamentos
                    where id = ?
                    """, lancamentoId);
            if (entry == null) {
                throw new AppException("Lancamento financeiro nao encontrado.");
            }
            if ("QUITADO".equals(entry.get("status"))) {
                throw new AppException("Este lancamento ja esta quitado.");
            }
            BigDecimal total = money(entry.get("valor_total"));
            BigDecimal baixado = money(entry.get("valor_baixado"));
            BigDecimal emAberto = total.subtract(baixado).setScale(2, RoundingMode.HALF_UP);
            if (valor.compareTo(emAberto) > 0) {
                throw new AppException("O valor da baixa excede o saldo em aberto.");
            }
            BigDecimal novoBaixado = baixado.add(valor).setScale(2, RoundingMode.HALF_UP);
            String novoStatus = novoBaixado.compareTo(total) >= 0 ? "QUITADO" : "PARCIAL";
            update("""
                    update financeiro_lancamentos
                    set valor_baixado = ?, status = ?, forma_baixa = ?, observacao = coalesce(?, observacao), baixado_em = ?
                    where id = ?
                    """, novoBaixado, novoStatus, formaBaixa.toUpperCase(Locale.ROOT), blankToNull(observacao), now(), lancamentoId);
            return null;
        });
    }

    public Map<String, BigDecimal> dueSummary() throws Exception {
        Map<String, BigDecimal> summary = new LinkedHashMap<>();
        summary.put("pagar_aberto", sum("select coalesce(sum(valor_total - valor_baixado),0) from financeiro_lancamentos where tipo='PAGAR' and status in ('ABERTO','PARCIAL')"));
        summary.put("receber_aberto", sum("select coalesce(sum(valor_total - valor_baixado),0) from financeiro_lancamentos where tipo='RECEBER' and status in ('ABERTO','PARCIAL')"));
        summary.put("vencendo_hoje", sum("select coalesce(sum(valor_total - valor_baixado),0) from financeiro_lancamentos where status in ('ABERTO','PARCIAL') and date(vencimento)=date('now')"));
        summary.put("atrasado", sum("select coalesce(sum(valor_total - valor_baixado),0) from financeiro_lancamentos where status in ('ABERTO','PARCIAL') and date(vencimento)<date('now')"));
        return summary;
    }

    public Map<String, BigDecimal> dailySettlementSummary(LocalDate data) throws Exception {
        Map<String, BigDecimal> summary = new LinkedHashMap<>();
        summary.put("contas_pagas", sum("""
                select coalesce(sum(valor_baixado),0) from financeiro_lancamentos
                where tipo='PAGAR' and baixado_em is not null and date(baixado_em)=date(?)
                """, data.toString()));
        summary.put("contas_recebidas", sum("""
                select coalesce(sum(valor_baixado),0) from financeiro_lancamentos
                where tipo='RECEBER' and baixado_em is not null and date(baixado_em)=date(?)
                """, data.toString()));
        summary.put("contas_pagas_dinheiro", sum("""
                select coalesce(sum(valor_baixado),0) from financeiro_lancamentos
                where tipo='PAGAR' and forma_baixa='DINHEIRO' and baixado_em is not null and date(baixado_em)=date(?)
                """, data.toString()));
        summary.put("contas_recebidas_dinheiro", sum("""
                select coalesce(sum(valor_baixado),0) from financeiro_lancamentos
                where tipo='RECEBER' and forma_baixa='DINHEIRO' and baixado_em is not null and date(baixado_em)=date(?)
                """, data.toString()));
        return summary;
    }

    public List<Map<String, Object>> pendingEntries(String tipo) throws Exception {
        String sql = """
                select id as ID, tipo as Tipo, descricao as Descricao, coalesce(parceiro, '-') as Parceiro,
                       valor_total as Total, valor_baixado as Baixado,
                       (valor_total - valor_baixado) as Aberto, vencimento as Vencimento, status as Status
                from financeiro_lancamentos
                where status in ('ABERTO','PARCIAL')
                """;
        if (tipo != null && !tipo.isBlank()) {
            return rows(sql + " and tipo = ? order by date(vencimento), id", tipo.toUpperCase(Locale.ROOT));
        }
        return rows(sql + " order by date(vencimento), id");
    }

    private BigDecimal sum(String sql, Object... args) throws Exception {
        Map<String, Object> row = one(sql, args);
        if (row == null) {
            return BigDecimal.ZERO;
        }
        Object value = row.values().iterator().next();
        return money(value);
    }

    private BigDecimal money(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal scaled(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String now() {
        return LocalDateTime.now().toString();
    }

    private Object blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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

    public record FinanceEntryRequest(
            String tipo,
            String descricao,
            String parceiro,
            String categoria,
            BigDecimal valorTotal,
            String vencimento,
            String observacao,
            Long notaFiscalId
    ) {
        public FinanceEntryRequest(
                String tipo,
                String descricao,
                String parceiro,
                String categoria,
                BigDecimal valorTotal,
                String vencimento,
                String observacao
        ) {
            this(tipo, descricao, parceiro, categoria, valorTotal, vencimento, observacao, null);
        }
    }
}
