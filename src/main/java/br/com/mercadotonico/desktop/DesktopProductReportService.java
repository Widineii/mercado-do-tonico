package br.com.mercadotonico.desktop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consultas de ranking e curva ABC de produtos para relatorios diarios (vendas concluidas).
 */
public class DesktopProductReportService {
    public static final int RANKING_LIMIT = 20;

    private final Connection con;

    public DesktopProductReportService(Connection con) {
        this.con = con;
    }

    static String vendasDiaFilter(Long caixaId) {
        return """
                where v.status='CONCLUIDA' and date(v.timestamp)=date(?)
                """ + (caixaId == null ? "" : " and v.caixa_id=? ");
    }

    public static String sqlRankingPorQuantidade(Long caixaId, int limit) {
        return """
                select p.nome as Produto,
                       sum(vi.quantidade) as Quantidade,
                       sum(vi.quantidade * vi.preco_unitario) as Faturamento
                from venda_itens vi
                join produtos p on p.id = vi.produto_id
                join vendas v on v.id = vi.venda_id
                """ + vendasDiaFilter(caixaId) + """
                group by p.id
                order by Quantidade desc
                """ + "limit " + limit;
    }

    public static String sqlRankingPorFaturamento(Long caixaId, int limit) {
        return """
                select p.nome as Produto,
                       sum(vi.quantidade) as Quantidade,
                       sum(vi.quantidade * vi.preco_unitario) as Faturamento
                from venda_itens vi
                join produtos p on p.id = vi.produto_id
                join vendas v on v.id = vi.venda_id
                """ + vendasDiaFilter(caixaId) + """
                group by p.id
                order by Faturamento desc
                """ + "limit " + limit;
    }

    /**
     * Curva ABC por faturamento bruto dos itens: percentual do total, percentual acumulado,
     * classe A (ate 80% acumulado), B (acima de 80% ate 95%), C (acima de 95%).
     * Um unico produto no periodo recebe classe A.
     */
    public static String sqlCurvaAbcPorFaturamento(Long caixaId) {
        String filter = vendasDiaFilter(caixaId);
        return """
                with agg as (
                  select p.id as produto_id,
                         p.nome as nome,
                         sum(vi.quantidade) as quantidade,
                         sum(vi.quantidade * vi.preco_unitario) as faturamento
                  from venda_itens vi
                  join produtos p on p.id = vi.produto_id
                  join vendas v on v.id = vi.venda_id
                  """ + filter + """
                  group by p.id
                ),
                tot as (
                  select coalesce(sum(faturamento), 0) as total_fat from agg
                ),
                ranked as (
                  select a.nome as Produto,
                         a.quantidade as Quantidade,
                         a.faturamento as Faturamento,
                         t.total_fat,
                         sum(a.faturamento) over (order by a.faturamento desc
                           rows between unbounded preceding and current row) as fat_acum,
                         count(*) over () as n_produtos
                  from agg a
                  cross join tot t
                )
                select Produto,
                       Quantidade,
                       Faturamento,
                       round(100.0 * Faturamento / nullif(total_fat, 0), 2) as PctTotal,
                       round(100.0 * fat_acum / nullif(total_fat, 0), 2) as PctAcumulado,
                       case
                         when n_produtos = 1 then 'A'
                         when 100.0 * fat_acum / nullif(total_fat, 0) <= 80.0 then 'A'
                         when 100.0 * fat_acum / nullif(total_fat, 0) <= 95.0 then 'B'
                         else 'C'
                       end as Classe
                from ranked
                order by Faturamento desc
                """;
    }

    public static Object[] argsDiaCaixa(LocalDate dia, Long caixaId) {
        return caixaId == null ? new Object[]{dia.toString()} : new Object[]{dia.toString(), caixaId};
    }

    public List<Map<String, Object>> rankingByQuantity(LocalDate dia, Long caixaId, int limit) throws Exception {
        return queryMaps(sqlRankingPorQuantidade(caixaId, limit), argsDiaCaixa(dia, caixaId));
    }

    public List<Map<String, Object>> rankingByRevenue(LocalDate dia, Long caixaId, int limit) throws Exception {
        return queryMaps(sqlRankingPorFaturamento(caixaId, limit), argsDiaCaixa(dia, caixaId));
    }

    public List<Map<String, Object>> abcByRevenue(LocalDate dia, Long caixaId) throws Exception {
        return queryMaps(sqlCurvaAbcPorFaturamento(caixaId), argsDiaCaixa(dia, caixaId));
    }

    private List<Map<String, Object>> queryMaps(String sql, Object[] params) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                var md = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= md.getColumnCount(); c++) {
                        row.put(md.getColumnLabel(c), rs.getObject(c));
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }
}
