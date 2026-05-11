package br.com.mercadotonico.desktop;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
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
        BigDecimal descontoVal = money(venda.get("desconto"));
        BigDecimal totalVal = money(venda.get("total"));
        BigDecimal subtotalBruto = totalVal.add(descontoVal);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            final int W = 44;
            writer.write(repeat('=', W) + "\n");
            writer.write(center("MERCADO DO TONICO", W) + "\n");
            writer.write(center("Cupom nao fiscal / comprovante", W) + "\n");
            writer.write(repeat('=', W) + "\n");
            String linha1 = "Venda #" + venda.get("id") + "  " + LocalDateTime.parse(venda.get("timestamp").toString()).format(BR_DATE_TIME);
            writer.write(fitLine(linha1, W) + "\n");
            writer.write(fitLine("Caixa " + venda.get("caixa_numero") + "  |  " + venda.get("operador"), W) + "\n");
            writer.write(repeat('-', W) + "\n");
            writer.write("PRODUTO                              QTD    UNITARIO     TOTAL\n");
            writer.write(repeat('-', W) + "\n");
            for (Map<String, Object> item : itens) {
                BigDecimal qtd = money(item.get("quantidade"));
                BigDecimal preco = money(item.get("preco_unitario"));
                BigDecimal linha = preco.multiply(qtd);
                String nome = nomeResumido(item.get("nome").toString(), 28);
                writer.write(String.format(Locale.ROOT, "%-28s %6s %10s %12s%n",
                        nome,
                        qtd.stripTrailingZeros().toPlainString(),
                        BRL.format(preco),
                        BRL.format(linha)));
            }
            writer.write(repeat('-', W) + "\n");
            writer.write(String.format(Locale.ROOT, "%-28s %20s%n", "Subtotal produtos", BRL.format(subtotalBruto)));
            writer.write(String.format(Locale.ROOT, "%-28s %20s%n", "Desconto", BRL.format(descontoVal)));
            writer.write(String.format(Locale.ROOT, "%-28s %20s%n", "TOTAL A PAGAR", BRL.format(totalVal)));
            writer.write(repeat('-', W) + "\n");
            writer.write("Pagamentos:\n");
            for (Map<String, Object> pagamento : pagamentos) {
                writer.write(String.format(Locale.ROOT, "  %-12s  %s%n",
                        pagamento.get("forma"), BRL.format(money(pagamento.get("valor")))));
            }
            writer.write(repeat('-', W) + "\n");
            writer.write(center("Documento sem valor fiscal.", W) + "\n");
            writer.write(center("Obrigado pela preferencia!", W) + "\n");
            writer.write(repeat('=', W) + "\n");
        }
    }

    private void writePdf(File file, Map<String, Object> venda, List<Map<String, Object>> itens,
                          List<Map<String, Object>> pagamentos) throws Exception {
        BigDecimal descontoVal = money(venda.get("desconto"));
        BigDecimal totalVal = money(venda.get("total"));
        BigDecimal subtotalBruto = totalVal.add(descontoVal);
        Document document = new Document();
        PdfWriter.getInstance(document, new FileOutputStream(file));
        document.open();
        Paragraph title = new Paragraph("Mercado do Tonico", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15));
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);
        Paragraph sub = new Paragraph("Cupom nao fiscal — comprovante de venda", FontFactory.getFont(FontFactory.HELVETICA, 9));
        sub.setAlignment(Element.ALIGN_CENTER);
        sub.setSpacingAfter(6);
        document.add(sub);
        Paragraph meta = new Paragraph(
                "Venda #" + venda.get("id") + "   •   " + LocalDateTime.parse(venda.get("timestamp").toString()).format(BR_DATE_TIME),
                FontFactory.getFont(FontFactory.HELVETICA, 9));
        meta.setAlignment(Element.ALIGN_CENTER);
        document.add(meta);
        Paragraph cx = new Paragraph(
                "Caixa " + venda.get("caixa_numero") + "   •   Operador: " + venda.get("operador"),
                FontFactory.getFont(FontFactory.HELVETICA, 9));
        cx.setAlignment(Element.ALIGN_CENTER);
        cx.setSpacingAfter(10);
        document.add(cx);

        PdfPTable grid = new PdfPTable(new float[]{3.4f, 0.85f, 1.35f, 1.45f});
        grid.setWidthPercentage(100);
        grid.setSpacingAfter(8);
        headerCell(grid, "Produto");
        headerCell(grid, "Qtd");
        headerCell(grid, "Unit.");
        headerCell(grid, "Total");
        for (Map<String, Object> item : itens) {
            BigDecimal qtd = money(item.get("quantidade"));
            BigDecimal preco = money(item.get("preco_unitario"));
            BigDecimal linha = preco.multiply(qtd);
            bodyCell(grid, item.get("nome").toString(), Element.ALIGN_LEFT);
            bodyCell(grid, qtd.stripTrailingZeros().toPlainString(), Element.ALIGN_RIGHT);
            bodyCell(grid, BRL.format(preco), Element.ALIGN_RIGHT);
            bodyCell(grid, BRL.format(linha), Element.ALIGN_RIGHT);
        }
        document.add(grid);

        PdfPTable tot = new PdfPTable(new float[]{3f, 1.05f});
        tot.setWidthPercentage(72);
        tot.setHorizontalAlignment(Element.ALIGN_RIGHT);
        summaryRow(tot, "Subtotal produtos", subtotalBruto, false);
        summaryRow(tot, "Desconto", descontoVal, false);
        summaryRow(tot, "TOTAL A PAGAR", totalVal, true);
        document.add(tot);

        document.add(new Paragraph(" ", FontFactory.getFont(FontFactory.HELVETICA, 6)));
        Paragraph pgTitle = new Paragraph("Pagamentos", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9));
        document.add(pgTitle);
        for (Map<String, Object> pagamento : pagamentos) {
            document.add(new Paragraph("  • " + pagamento.get("forma") + ": " + BRL.format(money(pagamento.get("valor"))),
                    FontFactory.getFont(FontFactory.HELVETICA, 9)));
        }
        document.add(new Paragraph(" ", FontFactory.getFont(FontFactory.HELVETICA, 8)));
        Paragraph rod = new Paragraph("Documento sem valor fiscal.\nObrigado pela preferencia!",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8));
        rod.setAlignment(Element.ALIGN_CENTER);
        document.add(rod);
        document.close();
    }

    private static void headerCell(PdfPTable table, String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8)));
        c.setBackgroundColor(new java.awt.Color(244, 247, 250));
        c.setPadding(5);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setMinimumHeight(20);
        table.addCell(c);
    }

    private static void bodyCell(PdfPTable table, String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA, 8)));
        c.setHorizontalAlignment(align);
        c.setVerticalAlignment(Element.ALIGN_TOP);
        c.setPadding(4);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(new java.awt.Color(220, 228, 236));
        table.addCell(c);
    }

    private static void summaryRow(PdfPTable table, String label, BigDecimal value, boolean emphasize) {
        var font = FontFactory.getFont(emphasize ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, emphasize ? 11 : 9);
        PdfPCell left = new PdfPCell(new Phrase(label, font));
        left.setBorder(Rectangle.NO_BORDER);
        left.setPaddingTop(emphasize ? 8 : 2);
        left.setPaddingBottom(2);
        PdfPCell right = new PdfPCell(new Phrase(BRL.format(value), font));
        right.setBorder(Rectangle.TOP);
        if (emphasize) {
            right.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
            right.setBorderWidthTop(1.2f);
        }
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        right.setPaddingTop(emphasize ? 8 : 2);
        right.setPaddingBottom(2);
        table.addCell(left);
        table.addCell(right);
    }

    private static String repeat(char c, int n) {
        if (n <= 0) {
            return "";
        }
        return String.valueOf(c).repeat(n);
    }

    private static String center(String s, int width) {
        if (s.length() >= width) {
            return s.substring(0, width);
        }
        int pad = (width - s.length()) / 2;
        return repeat(' ', pad) + s;
    }

    private static String fitLine(String s, int width) {
        if (s.length() <= width) {
            return s;
        }
        return s.substring(0, width - 1) + ".";
    }

    private static String nomeResumido(String nome, int max) {
        String n = nome.replace('\n', ' ').trim();
        if (n.length() <= max) {
            return n;
        }
        return n.substring(0, max - 1) + ".";
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
