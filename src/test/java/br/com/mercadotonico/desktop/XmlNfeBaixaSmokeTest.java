package br.com.mercadotonico.desktop;

import br.com.mercadotonico.db.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Reproduz caminho minimo da baixa NF-e: financeiro com nota_fiscal_id + FK.
 */
class XmlNfeBaixaSmokeTest {

    @Test
    void createFinanceEntryWithNotaFiscalId_shouldSucceedAfterMigrations() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = con.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            new MigrationRunner().migrate(con);
            try (Statement st = con.createStatement()) {
                st.executeUpdate("""
                        insert into usuarios (id, nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero)
                        values (7, 'Teste', 'teste_xml', 'x', 'ADMIN', 1, 30, 1)
                        """);
                st.executeUpdate("""
                        insert into fornecedores (id, razao_social, nome_fantasia, cnpj)
                        values (50, 'Fornecedor Teste XML', 'Fantasia', '11222333000180')
                        """);
                st.executeUpdate("""
                        insert into notas_fiscais (id, fornecedor_id, numero_nf, data, xml_path, total, importado_em, chave_acesso, status)
                        values (1, 50, '999888', '2026-05-12T10:30:00-03:00', '/tmp/nfe.xml', 450, '%s', '35260511222333000180550010009998881234543210', 'PENDENTE')
                        """.formatted(LocalDateTime.now().toString()));
            }
            try (var ps = con.prepareStatement("""
                    insert into financeiro_lancamentos
                    (tipo, descricao, parceiro, categoria, valor_total, valor_baixado, vencimento, status, observacao, criado_por, criado_em, nota_fiscal_id)
                    values ('PAGAR', 'NF-e teste', 'Fornecedor', 'Fornecedor / NF-e', 450, 0, '2026-07-10', 'ABERTO', 'obs', 7, ?, 1)
                    """)) {
                ps.setString(1, LocalDateTime.now().toString());
                ps.executeUpdate();
            }
            DesktopFinanceService finance = new DesktopFinanceService(con);
            assertDoesNotThrow(() -> finance.createEntry(
                    new DesktopFinanceService.FinanceEntryRequest(
                            "PAGAR",
                            "NF-e teste 2",
                            "Fornecedor",
                            "Fornecedor / NF-e",
                            new BigDecimal("10.00"),
                            "2026-07-11",
                            "obs2",
                            1L
                    ),
                    7L
            ));
        }
    }

    @Test
    void dupQueryByNotaFiscalId_shouldRun() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = con.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            new MigrationRunner().migrate(con);
            try (Statement st = con.createStatement()) {
                st.executeUpdate("""
                        insert into usuarios (id, nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero)
                        values (7, 'Teste', 'teste_xml', 'x', 'ADMIN', 1, 30, 1)
                        """);
                st.executeUpdate("""
                        insert into fornecedores (id, razao_social, nome_fantasia, cnpj)
                        values (50, 'Fornecedor Teste XML', 'Fantasia', '11222333000180')
                        """);
                st.executeUpdate("""
                        insert into notas_fiscais (id, fornecedor_id, numero_nf, data, xml_path, total, importado_em, chave_acesso, status)
                        values (1, 50, '999888', '2026-05-12', '/tmp/nfe.xml', 450, '%s', 'x', 'PENDENTE')
                        """.formatted(LocalDateTime.now().toString()));
            }
            try (var ps = con.prepareStatement(
                    "select id from financeiro_lancamentos where nota_fiscal_id=? limit 1")) {
                ps.setLong(1, 1L);
                assertDoesNotThrow(() -> ps.executeQuery().close());
            }
        }
    }

    @Test
    void parseSampleXmlFromDisk_shouldHaveFourDet() throws Exception {
        File f = new File("data/xml_nfe_entrada/NFe-teste-distribuidora-4-itens.xml");
        if (!f.exists()) {
            return;
        }
        var doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
        doc.getDocumentElement().normalize();
        int n = doc.getElementsByTagName("det").getLength();
        if (n != 4) {
            throw new AssertionError("Esperado 4 <det>, obtido " + n);
        }
    }
}
