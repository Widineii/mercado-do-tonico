package br.com.mercadotonico.desktop;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class DesktopCashReportService {
    private final Connection con;

    public DesktopCashReportService(Connection con) {
        this.con = con;
    }

    public Map<String, BigDecimal> dailySummary(Long caixaId, LocalDate data) throws Exception {
        String filter = caixaId == null ? "" : " and v.caixa_id = ? ";
        String filterOps = caixaId == null ? "" : " and caixa_id = ? ";
        String filterCaixas = caixaId == null ? "" : " and id = ? ";

        Map<String, BigDecimal> summary = new LinkedHashMap<>();
        summary.put("abertura", sum("""
            select coalesce(sum(abertura_valor),0) from caixas
            where abertura_timestamp is not null and date(abertura_timestamp) = date(?)
            """ + filterCaixas, data.toString(), caixaId));
        summary.put("dinheiro", sum("""
            select coalesce(sum(vp.valor),0) from venda_pagamentos vp
            join vendas v on v.id = vp.venda_id
            where date(v.timestamp) = date(?) and v.status = 'CONCLUIDA' and vp.forma = 'DINHEIRO'
            """ + filter, data.toString(), caixaId));
        summary.put("debito", sum("""
            select coalesce(sum(vp.valor),0) from venda_pagamentos vp
            join vendas v on v.id = vp.venda_id
            where date(v.timestamp) = date(?) and v.status = 'CONCLUIDA' and vp.forma = 'DEBITO'
            """ + filter, data.toString(), caixaId));
        summary.put("credito", sum("""
            select coalesce(sum(vp.valor),0) from venda_pagamentos vp
            join vendas v on v.id = vp.venda_id
            where date(v.timestamp) = date(?) and v.status = 'CONCLUIDA' and vp.forma = 'CREDITO'
            """ + filter, data.toString(), caixaId));
        summary.put("pix", sum("""
            select coalesce(sum(vp.valor),0) from venda_pagamentos vp
            join vendas v on v.id = vp.venda_id
            where date(v.timestamp) = date(?) and v.status = 'CONCLUIDA' and vp.forma = 'PIX'
            """ + filter, data.toString(), caixaId));
        summary.put("fiado", sum("""
            select coalesce(sum(vp.valor),0) from venda_pagamentos vp
            join vendas v on v.id = vp.venda_id
            where date(v.timestamp) = date(?) and v.status = 'CONCLUIDA' and vp.forma = 'FIADO'
            """ + filter, data.toString(), caixaId));
        summary.put("recebimentos_fiado", sum("""
            select coalesce(sum(fp.valor),0) from fiado_pagamentos fp
            where date(fp.data) = date(?)
            """, data.toString()));
        summary.put("sangria", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where date(timestamp) = date(?) and tipo = 'SANGRIA'
            """ + filterOps, data.toString(), caixaId));
        summary.put("suprimento", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where date(timestamp) = date(?) and tipo = 'SUPRIMENTO'
            """ + filterOps, data.toString(), caixaId));
        summary.put("contado_fechamento", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where date(timestamp) = date(?) and tipo = 'FECHAMENTO'
            """ + filterOps, data.toString(), caixaId));
        summary.put("devolucao_dinheiro", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where date(timestamp) = date(?) and tipo = 'DEVOLUCAO_DINHEIRO'
            """ + filterOps, data.toString(), caixaId));
        summary.put("devolucao_pix", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where date(timestamp) = date(?) and tipo = 'DEVOLUCAO_PIX'
            """ + filterOps, data.toString(), caixaId));
        summary.put("devolucao_debito", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where date(timestamp) = date(?) and tipo = 'DEVOLUCAO_DEBITO'
            """ + filterOps, data.toString(), caixaId));
        summary.put("devolucao_credito", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where date(timestamp) = date(?) and tipo = 'DEVOLUCAO_CREDITO'
            """ + filterOps, data.toString(), caixaId));
        summary.put("abate_fiado", sum("""
            select coalesce(sum(valor_total),0) from devolucoes
            where date(criado_em) = date(?) and forma_destino = 'ABATER_FIADO'
            """ + (caixaId == null ? "" : " and caixa_id = ? "), data.toString(), caixaId));
        summary.put("vale_troca_emitido", sum("""
            select coalesce(sum(valor_total),0) from devolucoes
            where date(criado_em) = date(?) and forma_destino = 'VALE_TROCA'
            """ + (caixaId == null ? "" : " and caixa_id = ? "), data.toString(), caixaId));
        summary.put("vendas_totais", sum("""
            select coalesce(sum(total),0) from vendas v
            where date(v.timestamp) = date(?) and v.status = 'CONCLUIDA'
            """ + filter, data.toString(), caixaId));
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

        BigDecimal esperado = summary.get("abertura")
                .add(summary.get("dinheiro"))
                .add(summary.get("suprimento"))
                .add(summary.get("contas_recebidas_dinheiro"))
                .subtract(summary.get("sangria"))
                .subtract(summary.get("devolucao_dinheiro"))
                .subtract(summary.get("contas_pagas_dinheiro"));
        summary.put("esperado_dinheiro", esperado);
        summary.put("divergencia", summary.get("contado_fechamento").subtract(esperado));
        return summary;
    }

    /**
     * Mesmas chaves que {@link #dailySummary(Long, LocalDate)}, porem agregando
     * desde {@code caixas.abertura_timestamp} da abertura atual (turno).
     * Assim vendas e operacoes depois da meia-noite continuam no mesmo caixa
     * ate o fechamento, sem cortar pelo dia civil.
     * <p>Se nao houver {@code abertura_timestamp}, usa meia-noite do dia civil
     * atual como inicio sintetico do turno (mesma regra do PDV).</p>
     */
    public Map<String, BigDecimal> sessionSummarySinceOpening(long caixaId) throws Exception {
        String desde;
        BigDecimal aberturaValor;
        try (PreparedStatement ps = con.prepareStatement(
                "select abertura_timestamp, coalesce(abertura_valor,0) from caixas where id=?")) {
            ps.setLong(1, caixaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return dailySummary(caixaId, LocalDate.now());
                }
                desde = rs.getString(1);
                Object abObj = rs.getObject(2);
                aberturaValor = abObj == null ? BigDecimal.ZERO : new BigDecimal(abObj.toString());
            }
        }
        if (desde == null || desde.isBlank()) {
            // Caixa aberto sem carimbo de abertura (legado): usa inicio do dia local
            // para o turno nao "pular" vendas ao reabrir o programa.
            desde = LocalDate.now().atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }

        Map<String, BigDecimal> summary = new LinkedHashMap<>();
        summary.put("abertura", aberturaValor);
        summary.put("dinheiro", sum("""
            select coalesce(sum(vp.valor),0) from venda_pagamentos vp
            join vendas v on v.id = vp.venda_id
            where v.caixa_id = ? and v.status = 'CONCLUIDA' and vp.forma = 'DINHEIRO'
              and strftime('%s', v.timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("debito", sum("""
            select coalesce(sum(vp.valor),0) from venda_pagamentos vp
            join vendas v on v.id = vp.venda_id
            where v.caixa_id = ? and v.status = 'CONCLUIDA' and vp.forma = 'DEBITO'
              and strftime('%s', v.timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("credito", sum("""
            select coalesce(sum(vp.valor),0) from venda_pagamentos vp
            join vendas v on v.id = vp.venda_id
            where v.caixa_id = ? and v.status = 'CONCLUIDA' and vp.forma = 'CREDITO'
              and strftime('%s', v.timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("pix", sum("""
            select coalesce(sum(vp.valor),0) from venda_pagamentos vp
            join vendas v on v.id = vp.venda_id
            where v.caixa_id = ? and v.status = 'CONCLUIDA' and vp.forma = 'PIX'
              and strftime('%s', v.timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("fiado", sum("""
            select coalesce(sum(vp.valor),0) from venda_pagamentos vp
            join vendas v on v.id = vp.venda_id
            where v.caixa_id = ? and v.status = 'CONCLUIDA' and vp.forma = 'FIADO'
              and strftime('%s', v.timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("recebimentos_fiado", sum("""
            select coalesce(sum(fp.valor),0) from fiado_pagamentos fp
            where strftime('%s', fp.data) >= strftime('%s', ?)
            """, desde));
        summary.put("sangria", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where caixa_id = ? and tipo = 'SANGRIA' and strftime('%s', timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("suprimento", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where caixa_id = ? and tipo = 'SUPRIMENTO' and strftime('%s', timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("contado_fechamento", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where caixa_id = ? and tipo = 'FECHAMENTO' and strftime('%s', timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("devolucao_dinheiro", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where caixa_id = ? and tipo = 'DEVOLUCAO_DINHEIRO' and strftime('%s', timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("devolucao_pix", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where caixa_id = ? and tipo = 'DEVOLUCAO_PIX' and strftime('%s', timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("devolucao_debito", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where caixa_id = ? and tipo = 'DEVOLUCAO_DEBITO' and strftime('%s', timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("devolucao_credito", sum("""
            select coalesce(sum(valor),0) from caixa_operacoes
            where caixa_id = ? and tipo = 'DEVOLUCAO_CREDITO' and strftime('%s', timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("abate_fiado", sum("""
            select coalesce(sum(valor_total),0) from devolucoes
            where caixa_id = ? and forma_destino = 'ABATER_FIADO' and strftime('%s', criado_em) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("vale_troca_emitido", sum("""
            select coalesce(sum(valor_total),0) from devolucoes
            where caixa_id = ? and forma_destino = 'VALE_TROCA' and strftime('%s', criado_em) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("vendas_totais", sum("""
            select coalesce(sum(total),0) from vendas v
            where v.caixa_id = ? and v.status = 'CONCLUIDA' and strftime('%s', v.timestamp) >= strftime('%s', ?)
            """, caixaId, desde));
        summary.put("contas_pagas", sum("""
            select coalesce(sum(valor_baixado),0) from financeiro_lancamentos
            where tipo='PAGAR' and baixado_em is not null and strftime('%s', baixado_em) >= strftime('%s', ?)
            """, desde));
        summary.put("contas_recebidas", sum("""
            select coalesce(sum(valor_baixado),0) from financeiro_lancamentos
            where tipo='RECEBER' and baixado_em is not null and strftime('%s', baixado_em) >= strftime('%s', ?)
            """, desde));
        summary.put("contas_pagas_dinheiro", sum("""
            select coalesce(sum(valor_baixado),0) from financeiro_lancamentos
            where tipo='PAGAR' and forma_baixa='DINHEIRO' and baixado_em is not null and strftime('%s', baixado_em) >= strftime('%s', ?)
            """, desde));
        summary.put("contas_recebidas_dinheiro", sum("""
            select coalesce(sum(valor_baixado),0) from financeiro_lancamentos
            where tipo='RECEBER' and forma_baixa='DINHEIRO' and baixado_em is not null and strftime('%s', baixado_em) >= strftime('%s', ?)
            """, desde));

        BigDecimal esperado = summary.get("abertura")
                .add(summary.get("dinheiro"))
                .add(summary.get("suprimento"))
                .add(summary.get("contas_recebidas_dinheiro"))
                .subtract(summary.get("sangria"))
                .subtract(summary.get("devolucao_dinheiro"))
                .subtract(summary.get("contas_pagas_dinheiro"));
        summary.put("esperado_dinheiro", esperado);
        summary.put("divergencia", summary.get("contado_fechamento").subtract(esperado));
        return summary;
    }

    private BigDecimal sum(String sql, Object... params) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int index = 1;
            for (Object param : params) {
                if (param != null) {
                    ps.setObject(index++, param);
                }
            }
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getObject(1) != null ? new BigDecimal(rs.getObject(1).toString()) : BigDecimal.ZERO;
        }
    }
}
