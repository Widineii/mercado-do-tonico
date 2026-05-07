package br.com.mercadotonico.service;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Service
public class RelatorioService {
    private final JdbcTemplate jdbc;

    public RelatorioService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> vendasPorPeriodo(String inicio, String fim) {
        return jdbc.queryForList("""
            select v.id, v.timestamp, c.numero as caixa, u.nome as operador, v.total, v.forma_pagamento, v.status
            from vendas v join caixas c on c.id = v.caixa_id join usuarios u on u.id = v.operador_id
            where date(v.timestamp) between date(?) and date(?)
            order by v.timestamp desc
            """, inicio, fim);
    }

    public List<Map<String, Object>> maisVendidos() {
        return jdbc.queryForList("""
            select p.nome, sum(vi.quantidade) as quantidade, sum(vi.quantidade * vi.preco_unitario) as total
            from venda_itens vi join produtos p on p.id = vi.produto_id join vendas v on v.id = vi.venda_id
            where v.status = 'CONCLUIDA'
            group by p.id, p.nome order by quantidade desc limit 20
            """);
    }

    public List<Map<String, Object>> lucroPorPeriodo(String inicio, String fim) {
        return jdbc.queryForList("""
            select date(v.timestamp) as dia,
                   sum(vi.quantidade * vi.preco_unitario) as receita,
                   sum(vi.quantidade * vi.custo_unitario) as custo,
                   sum(vi.quantidade * (vi.preco_unitario - vi.custo_unitario)) as lucro
            from venda_itens vi join vendas v on v.id = vi.venda_id
            where v.status = 'CONCLUIDA' and date(v.timestamp) between date(?) and date(?)
            group by date(v.timestamp) order by dia desc
            """, inicio, fim);
    }

    public List<Map<String, Object>> estoqueAtual() {
        return jdbc.queryForList("select nome, codigo_barras, categoria, estoque_atual, estoque_minimo, preco_venda from produtos order by nome");
    }

    public String csv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(";", rows.get(0).keySet())).append("\n");
        for (Map<String, Object> row : rows) {
            StringJoiner joiner = new StringJoiner(";");
            row.values().forEach(v -> joiner.add(v == null ? "" : v.toString().replace(";", ",")));
            sb.append(joiner).append("\n");
        }
        return sb.toString();
    }

    public byte[] pdf(String titulo, List<Map<String, Object>> rows) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document();
            PdfWriter.getInstance(doc, out);
            doc.open();
            doc.add(new Paragraph("Mercado do Tonico"));
            doc.add(new Paragraph(titulo + " - " + LocalDate.now()));
            doc.add(new Paragraph(" "));
            for (Map<String, Object> row : rows) {
                doc.add(new Paragraph(row.toString()));
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Nao foi possivel gerar o PDF", e);
        }
    }
}
