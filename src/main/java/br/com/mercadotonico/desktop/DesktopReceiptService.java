package br.com.mercadotonico.desktop;

import com.lowagie.text.Document;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DesktopReceiptService {
    private static final NumberFormat BRL = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter BR_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final Connection con;
    private final File baseDir;

    public DesktopReceiptService(Connection con) {
        this(con, new File("data/comprovantes"));
    }

    public DesktopReceiptService(Connection con, File baseDir) {
        this.con = con;
        this.baseDir = baseDir;
    }

    public ReceiptFiles generateForSale(long vendaId) throws Exception {
        Map<String, Object> venda = one("""
                select v.id, v.total, v.desconto, v.forma_pagamento, v.timestamp,
                       c.numero as caixa_numero, u.nome as operador
                from vendas v
                join caixas c on c.id = v.caixa_id
                join usuarios u on u.id = v.operador_id
                where v.id = ?
                """, vendaId);
        List<Map<String, Object>> itens = rows("""
                select p.nome, vi.quantidade, vi.preco_unitario
                from venda_itens vi
                join produtos p on p.id = vi.produto_id
                where vi.venda_id = ?
                order by vi.id
                """, vendaId);
        List<Map<String, Object>> pagamentos = rows("""
                select forma, valor
                from venda_pagamentos
                where venda_id = ?
                order by id
                """, vendaId);

        baseDir.mkdirs();
        String stamp = FILE_TIME.format(LocalDateTime.now());
        File txt = new File(baseDir, "venda-" + vendaId + "-" + stamp + ".txt");
        File pdf = new File(baseDir, "venda-" + vendaId + "-" + stamp + ".pdf");

        writeTxt(txt, venda, itens, pagamentos);
        writePdf(pdf, venda, itens, pagamentos);
        upsertReceipt(vendaId, txt.getAbsolutePath(), pdf.getAbsolutePath());
        return new ReceiptFiles(txt, pdf);
    }

    private void writeTxt(File file, Map<String, Object> venda, List<Map<String, Object>> itens,
                          List<Map<String, Object>> pagamentos) throws Exception {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write("MERCADO DO TONICO\n");
            writer.write("Comprovante simples de venda\n");
            writer.write("Venda: #" + venda.get("id") + "\n");
            writer.write("Data: " + LocalDateTime.parse(venda.get("timestamp").toString()).format(BR_DATE_TIME) + "\n");
            writer.write("Caixa: " + venda.get("caixa_numero") + "\n");
            writer.write("Operador: " + venda.get("operador") + "\n");
            writer.write("----------------------------------------\n");
            for (Map<String, Object> item : itens) {
                BigDecimal qtd = money(item.get("quantidade"));
                BigDecimal preco = money(item.get("preco_unitario"));
                writer.write(item.get("nome") + "\n");
                writer.write("  " + qtd.stripTrailingZeros().toPlainString() + " x " + BRL.format(preco)
                        + " = " + BRL.format(preco.multiply(qtd)) + "\n");
            }
            writer.write("----------------------------------------\n");
            writer.write("Desconto: " + BRL.format(money(venda.get("desconto"))) + "\n");
            writer.write("Total: " + BRL.format(money(venda.get("total"))) + "\n");
            writer.write("Pagamentos:\n");
            for (Map<String, Object> pagamento : pagamentos) {
                writer.write("  - " + pagamento.get("forma") + ": " + BRL.format(money(pagamento.get("valor"))) + "\n");
            }
            writer.write("----------------------------------------\n");
            writer.write("Obrigado pela preferencia.\n");
        }
    }

    private void writePdf(File file, Map<String, Object> venda, List<Map<String, Object>> itens,
                          List<Map<String, Object>> pagamentos) throws Exception {
        Document document = new Document();
        PdfWriter.getInstance(document, new FileOutputStream(file));
        document.open();
        document.add(new Paragraph("Mercado do Tonico", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16)));
        document.add(new Paragraph("Comprovante simples de venda"));
        document.add(new Paragraph("Venda: #" + venda.get("id")));
        document.add(new Paragraph("Data: " + LocalDateTime.parse(venda.get("timestamp").toString()).format(BR_DATE_TIME)));
        document.add(new Paragraph("Caixa: " + venda.get("caixa_numero") + " | Operador: " + venda.get("operador")));
        document.add(new Paragraph(" "));
        for (Map<String, Object> item : itens) {
            BigDecimal qtd = money(item.get("quantidade"));
            BigDecimal preco = money(item.get("preco_unitario"));
            document.add(new Paragraph(item.get("nome").toString()));
            document.add(new Paragraph("  " + qtd.stripTrailingZeros().toPlainString() + " x " + BRL.format(preco)
                    + " = " + BRL.format(preco.multiply(qtd))));
        }
        document.add(new Paragraph(" "));
        document.add(new Paragraph("Desconto: " + BRL.format(money(venda.get("desconto")))));
        document.add(new Paragraph("Total: " + BRL.format(money(venda.get("total")))));
        document.add(new Paragraph("Pagamentos:"));
        for (Map<String, Object> pagamento : pagamentos) {
            document.add(new Paragraph("  - " + pagamento.get("forma") + ": " + BRL.format(money(pagamento.get("valor")))));
        }
        document.close();
    }

    private void upsertReceipt(long vendaId, String txtPath, String pdfPath) throws Exception {
        try (PreparedStatement ps = con.prepareStatement("""
                insert into comprovantes_venda (venda_id, arquivo_txt, arquivo_pdf, gerado_em)
                values (?, ?, ?, ?)
                on conflict(venda_id) do update set
                  arquivo_txt=excluded.arquivo_txt,
                  arquivo_pdf=excluded.arquivo_pdf,
                  gerado_em=excluded.gerado_em
                """)) {
            ps.setLong(1, vendaId);
            ps.setString(2, txtPath);
            ps.setString(3, pdfPath);
            ps.setString(4, LocalDateTime.now().toString());
            ps.executeUpdate();
        }
    }

    private BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private Map<String, Object> one(String sql, Object... args) throws Exception {
        List<Map<String, Object>> data = rows(sql, args);
        return data.isEmpty() ? null : data.get(0);
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

    private void bind(PreparedStatement ps, Object... args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    public record ReceiptFiles(File txtFile, File pdfFile) {}
}
