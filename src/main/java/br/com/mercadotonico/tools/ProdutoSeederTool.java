package br.com.mercadotonico.tools;

import br.com.mercadotonico.db.MigrationRunner;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Ferramenta CLI: substitui o catalogo de produtos atual por um CSV.
 *
 * <h3>Como rodar</h3>
 * <pre>
 *   mvnw exec:java "-Dexec.mainClass=br.com.mercadotonico.tools.ProdutoSeederTool"
 *
 *   # com argumentos opcionais
 *   mvnw exec:java -Dexec.mainClass=br.com.mercadotonico.tools.ProdutoSeederTool ^
 *        "-Dexec.args=data\seed\produtos.csv"
 * </pre>
 *
 * <p><b>Estrategia segura:</b> em vez de DELETE FROM produtos (que quebra
 * por foreign keys de vendas/movimentacoes), faz <i>soft delete</i>:
 * marca os produtos atuais como inativos e renomeia codigo_barras / sku /
 * codigo_interno com prefixo {@code OLD-} para liberar os UNIQUE indexes.
 * O historico de vendas continua integro.</p>
 *
 * <p>Sempre faz backup do arquivo .db em {@code backups/} antes de mexer.</p>
 *
 * <p>Formato do CSV (cabecalho obrigatorio):
 * {@code nome,marca,codigoBarras,categoria,subcategoria,unidade,
 * quantidadeEmbalagem,precoCusto,precoVenda,estoqueAtual,estoqueMinimo,
 * ncm,codigoVerificado}</p>
 */
public final class ProdutoSeederTool {

    private static final String DEFAULT_DB_URL = "jdbc:sqlite:data/mercado-tonico.db";
    private static final String DEFAULT_CSV    = "data/seed/produtos.csv";
    private static final String BACKUPS_DIR    = "backups";
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public static void main(String[] args) throws Exception {
        String csvArg = args.length > 0 ? args[0] : DEFAULT_CSV;
        String dbUrlArg = System.getenv().getOrDefault("MERCADO_DB_URL", DEFAULT_DB_URL);

        Path csvPath = Path.of(csvArg);
        if (!Files.isRegularFile(csvPath)) {
            System.err.println("[ERRO] CSV nao encontrado: " + csvPath.toAbsolutePath());
            System.exit(2);
        }

        System.out.println("==============================================");
        System.out.println(" Mercado do Tonico - Seed de Produtos");
        System.out.println("==============================================");
        System.out.println(" CSV : " + csvPath.toAbsolutePath());
        System.out.println(" DB  : " + dbUrlArg);
        System.out.println();

        // 1) backup do banco SQLite
        backupDatabase(dbUrlArg);

        // 2) carrega/atualiza schema (idempotente)
        try (Connection con = DriverManager.getConnection(dbUrlArg)) {
            con.createStatement().execute("PRAGMA foreign_keys = ON");
            new MigrationRunner().migrate(con);

            // 3) parse do CSV
            List<ProductRow> rows = parseCsv(csvPath);
            System.out.println("[INFO] CSV carregado: " + rows.size() + " produtos.");

            // 4) soft-delete dos atuais + insercao dos novos numa unica transacao
            int[] result = applyChanges(con, rows);
            int deactivated = result[0];
            int inserted   = result[1];
            int updated    = result[2];

            System.out.println();
            System.out.println("==============================================");
            System.out.println(" Resumo");
            System.out.println("==============================================");
            System.out.println(" Produtos antigos desativados : " + deactivated);
            System.out.println(" Produtos novos cadastrados   : " + inserted);
            System.out.println(" Produtos atualizados (EAN ja existia) : " + updated);

            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select count(*) from produtos where ativo = 1")) {
                rs.next();
                System.out.println(" Total ATIVO no banco agora   : " + rs.getInt(1));
            }
        }
        System.out.println();
        System.out.println("[OK] Seed concluido. Abra o sistema desktop para conferir.");
    }

    // -----------------------------------------------------------------
    // CSV parser (sem dependencia externa - tolera cabecalho com BOM)
    // -----------------------------------------------------------------

    private record ProductRow(
            String nome,
            String marca,
            String codigoBarras,
            String categoria,
            String subcategoria,
            String unidade,
            String quantidadeEmbalagem,
            BigDecimal precoCusto,
            BigDecimal precoVenda,
            BigDecimal estoqueAtual,
            BigDecimal estoqueMinimo,
            String ncm,
            boolean codigoVerificado) {}

    private static List<ProductRow> parseCsv(Path csv) throws IOException {
        List<String> linhas = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (linhas.isEmpty()) {
            throw new IOException("CSV vazio: " + csv);
        }
        List<ProductRow> rows = new ArrayList<>();
        String header = stripBom(linhas.get(0)).trim();
        if (!header.toLowerCase().startsWith("nome,marca,codigobarras")) {
            throw new IOException("Cabecalho do CSV inesperado: " + header);
        }
        for (int i = 1; i < linhas.size(); i++) {
            String line = linhas.get(i);
            if (line == null || line.isBlank()) continue;
            String[] cols = splitCsvLine(line);
            if (cols.length < 13) {
                System.err.println("[WARN] Linha " + (i + 1) + " ignorada (colunas insuficientes): " + line);
                continue;
            }
            try {
                rows.add(new ProductRow(
                        cols[0].trim(),
                        cols[1].trim(),
                        cols[2].trim().replaceAll("\\s+", ""),
                        cols[3].trim(),
                        cols[4].trim(),
                        cols[5].trim().toLowerCase(),
                        cols[6].trim(),
                        new BigDecimal(cols[7].trim().replace(",", ".")),
                        new BigDecimal(cols[8].trim().replace(",", ".")),
                        new BigDecimal(cols[9].trim().replace(",", ".")),
                        new BigDecimal(cols[10].trim().replace(",", ".")),
                        cols[11].trim(),
                        Boolean.parseBoolean(cols[12].trim())
                ));
            } catch (Exception parseError) {
                System.err.println("[WARN] Linha " + (i + 1) + " com erro ("
                        + parseError.getMessage() + "): " + line);
            }
        }
        return rows;
    }

    private static String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    /** Split CSV simples — suporta valores entre aspas duplas com vírgulas. */
    private static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    buf.append('"');
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if (c == ',' && !inQuote) {
                out.add(buf.toString());
                buf.setLength(0);
            } else {
                buf.append(c);
            }
        }
        out.add(buf.toString());
        return out.toArray(String[]::new);
    }

    // -----------------------------------------------------------------
    // Backup
    // -----------------------------------------------------------------

    private static void backupDatabase(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:sqlite:")) {
            System.out.println("[INFO] Backup ignorado (URL nao e SQLite local).");
            return;
        }
        try {
            String filePath = jdbcUrl.substring("jdbc:sqlite:".length()).trim();
            Path src = Path.of(filePath).toAbsolutePath();
            if (!Files.isRegularFile(src)) {
                System.out.println("[INFO] Banco ainda nao existe (" + src + "). Sera criado pela primeira execucao.");
                return;
            }
            Path backupsDir = Path.of(BACKUPS_DIR);
            Files.createDirectories(backupsDir);
            String stamp = LocalDateTime.now().format(STAMP);
            String fname = src.getFileName().toString().replaceFirst("\\.db$", "")
                    + "_pre_seed_" + stamp + ".db";
            Path dst = backupsDir.resolve(fname);
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[OK] Backup do banco gerado em: " + dst.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[WARN] Nao foi possivel gerar backup automatico: " + e.getMessage());
            System.err.println("       (continuo, mas se quiser cancele com Ctrl+C agora)");
        }
    }

    // -----------------------------------------------------------------
    // SQL: soft-delete + upsert
    // -----------------------------------------------------------------

    /** Retorna {desativados, inseridos, atualizados}. */
    private static int[] applyChanges(Connection con, List<ProductRow> rows) throws SQLException {
        int deactivated, inserted = 0, updated = 0;
        boolean prevAuto = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            // 1. Soft-delete dos antigos: marca ativo=0 e prefixa identificadores
            //    para liberar UNIQUE de codigo_barras / sku / codigo_interno.
            try (Statement st = con.createStatement()) {
                st.executeUpdate("""
                        UPDATE produtos
                           SET ativo = 0,
                               codigo_barras = CASE
                                   WHEN codigo_barras IS NULL OR codigo_barras LIKE 'OLD-%'
                                       THEN codigo_barras
                                   ELSE 'OLD-' || id || '-' || codigo_barras
                               END,
                               sku = CASE
                                   WHEN sku IS NULL OR sku LIKE 'OLD-%'
                                       THEN sku
                                   ELSE 'OLD-' || id || '-' || sku
                               END,
                               codigo_interno = CASE
                                   WHEN codigo_interno IS NULL OR codigo_interno LIKE 'OLD-%'
                                       THEN codigo_interno
                                   ELSE 'OLD-' || id || '-' || codigo_interno
                               END
                         WHERE ativo = 1
                        """);
            }
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select count(*) from produtos where ativo = 0 and codigo_barras like 'OLD-%'")) {
                rs.next();
                deactivated = rs.getInt(1);
            }

            // 2. Upsert por codigo_barras: insere os novos OU
            //    reativa+atualiza se o EAN bater com algum 'OLD-...-EAN'.
            try (PreparedStatement insert = con.prepareStatement("""
                    INSERT INTO produtos (
                        nome, codigo_barras, sku, codigo_interno,
                        marca, fabricante, categoria, unidade, ncm, cest,
                        preco_custo, preco_venda, estoque_atual, estoque_minimo,
                        localizacao, validade, observacoes, ativo,
                        controla_lote, lote_padrao, permite_preco_zero,
                        cadastrado_em
                    ) VALUES (
                        ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, NULL,
                        ?, ?, ?, ?,
                        NULL, NULL, ?, 1,
                        0, NULL, 0,
                        ?
                    )
                    """);
                 PreparedStatement reactivate = con.prepareStatement("""
                    UPDATE produtos
                       SET nome = ?, marca = ?, categoria = ?, unidade = ?,
                           ncm = ?, preco_custo = ?, preco_venda = ?,
                           estoque_atual = ?, estoque_minimo = ?,
                           observacoes = ?, ativo = 1,
                           codigo_barras = ?, codigo_interno = ?, sku = ?,
                           cadastrado_em = COALESCE(cadastrado_em, ?)
                     WHERE id = ?
                    """)) {

                int seq = 1;
                for (ProductRow r : rows) {
                    String now = LocalDateTime.now().toString();
                    Long existingOldId = findOldByBarcode(con, r.codigoBarras());

                    String codigoInterno = "P" + String.format("%05d", seq);
                    while (codigoInternoExists(con, codigoInterno)) {
                        seq++;
                        codigoInterno = "P" + String.format("%05d", seq);
                    }
                    String sku = r.codigoBarras();
                    String observacoes = buildObservacoes(r);

                    if (existingOldId != null) {
                        reactivate.setString(1, r.nome());
                        reactivate.setString(2, r.marca());
                        reactivate.setString(3, r.categoria());
                        reactivate.setString(4, r.unidade());
                        reactivate.setString(5, normalizeNcm(r.ncm()));
                        reactivate.setBigDecimal(6, r.precoCusto());
                        reactivate.setBigDecimal(7, r.precoVenda());
                        reactivate.setBigDecimal(8, r.estoqueAtual());
                        reactivate.setBigDecimal(9, r.estoqueMinimo());
                        reactivate.setString(10, observacoes);
                        reactivate.setString(11, r.codigoBarras());
                        reactivate.setString(12, codigoInterno);
                        reactivate.setString(13, sku);
                        reactivate.setString(14, now);
                        reactivate.setLong(15, existingOldId);
                        reactivate.executeUpdate();
                        updated++;
                    } else {
                        insert.setString(1, r.nome());
                        insert.setString(2, r.codigoBarras());
                        insert.setString(3, sku);
                        insert.setString(4, codigoInterno);
                        insert.setString(5, r.marca());
                        insert.setString(6, r.marca()); // fabricante = marca por padrao
                        insert.setString(7, r.categoria());
                        insert.setString(8, r.unidade());
                        insert.setString(9, normalizeNcm(r.ncm()));
                        insert.setBigDecimal(10, r.precoCusto());
                        insert.setBigDecimal(11, r.precoVenda());
                        insert.setBigDecimal(12, r.estoqueAtual());
                        insert.setBigDecimal(13, r.estoqueMinimo());
                        insert.setString(14, observacoes);
                        insert.setString(15, now);
                        insert.executeUpdate();
                        inserted++;
                    }
                    seq++;
                }
            }

            // 3. Garante categorias na tabela de referencia (autocomplete do PDV)
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT OR IGNORE INTO categorias (nome) " +
                    "SELECT DISTINCT categoria FROM produtos WHERE categoria IS NOT NULL")) {
                ps.executeUpdate();
            }

            con.commit();
            return new int[]{deactivated, inserted, updated};
        } catch (SQLException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(prevAuto);
        }
    }

    private static Long findOldByBarcode(Connection con, String barcode) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "select id from produtos where ativo = 0 and codigo_barras like ? limit 1")) {
            ps.setString(1, "OLD-%-" + barcode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return null;
    }

    private static boolean codigoInternoExists(Connection con, String codigo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "select 1 from produtos where codigo_interno = ? limit 1")) {
            ps.setString(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String normalizeNcm(String ncm) {
        if (ncm == null) return null;
        String digits = ncm.replaceAll("[^0-9]", "");
        return digits.isBlank() ? null : digits;
    }

    private static String buildObservacoes(ProductRow r) {
        StringBuilder sb = new StringBuilder();
        if (r.subcategoria() != null && !r.subcategoria().isBlank()) {
            sb.append("Subcategoria: ").append(r.subcategoria()).append(" | ");
        }
        if (r.quantidadeEmbalagem() != null && !r.quantidadeEmbalagem().isBlank()) {
            sb.append("Embalagem: ").append(r.quantidadeEmbalagem()).append(" | ");
        }
        sb.append("Importado em ").append(LocalDate.now().toString());
        if (r.codigoVerificado()) sb.append(" (EAN verificado)");
        return sb.toString();
    }

    private ProdutoSeederTool() {}
}
