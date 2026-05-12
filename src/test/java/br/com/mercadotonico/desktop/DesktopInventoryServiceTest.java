package br.com.mercadotonico.desktop;

import br.com.mercadotonico.db.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopInventoryServiceTest {
    @Test
    void shouldRegisterManualEntryAndUpdateAverageCost() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            DesktopInventoryService service = new DesktopInventoryService(con);

            long produtoId = service.saveProduct(
                    new DesktopInventoryService.ProductDraft(
                            "Teste", "123", "SKU1", "Mercearia", "un",
                            new BigDecimal("10"), new BigDecimal("15"), new BigDecimal("10"),
                            new BigDecimal("1"), "A1", "2026-12-31", ""
                    ),
                    1L,
                    "P99999"
            );

            service.registerStockEntry(
                    new DesktopInventoryService.StockEntryRequest(
                            produtoId, new BigDecimal("10"), new BigDecimal("20"),
                            "L001", "2026-12-31", "NF-1", "Compra fornecedor"
                    ),
                    1L
            );

            try (Statement st = con.createStatement()) {
                var rs = st.executeQuery("select estoque_atual, preco_custo from produtos where id = " + produtoId);
                assertTrue(rs.next());
                assertEquals(20, rs.getBigDecimal("estoque_atual").intValue());
                assertEquals(new BigDecimal("15"), rs.getBigDecimal("preco_custo"));
            }
        }
    }

    @Test
    void shouldReconcileInventoryToCountedBalance() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            DesktopInventoryService service = new DesktopInventoryService(con);

            long produtoId = service.saveProduct(
                    new DesktopInventoryService.ProductDraft(
                            "Teste", null, "SKU2", "Mercearia", "un",
                            new BigDecimal("10"), new BigDecimal("15"), new BigDecimal("8"),
                            new BigDecimal("1"), "A1", null, ""
                    ),
                    1L,
                    "P99998"
            );

            service.reconcileInventory(
                    new DesktopInventoryService.InventoryCountRequest(produtoId, new BigDecimal("5"), "Contagem geral"),
                    1L
            );

            try (Statement st = con.createStatement()) {
                var rs = st.executeQuery("select estoque_atual from produtos where id = " + produtoId);
                assertTrue(rs.next());
                assertEquals(5, rs.getBigDecimal("estoque_atual").intValue());
            }
        }
    }

    @Test
    void shouldKeepNearestExpiryWhenRegisteringLotEntries() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            DesktopInventoryService service = new DesktopInventoryService(con);

            long produtoId = service.saveProduct(
                    new DesktopInventoryService.ProductDraft(
                            "Validade", null, "SKU3", "Mercearia", "un",
                            new BigDecimal("10"), new BigDecimal("15"), new BigDecimal("1"),
                            new BigDecimal("1"), "A1", null, ""
                    ),
                    1L,
                    "P99997"
            );

            service.registerStockEntry(
                    new DesktopInventoryService.StockEntryRequest(
                            produtoId, BigDecimal.ONE, new BigDecimal("10"),
                            "L1", LocalDate.of(2026, 12, 20).toString(), "NF-1", "Primeiro lote"
                    ),
                    1L
            );
            service.registerStockEntry(
                    new DesktopInventoryService.StockEntryRequest(
                            produtoId, BigDecimal.ONE, new BigDecimal("10"),
                            "L2", LocalDate.of(2026, 11, 15).toString(), "NF-2", "Segundo lote"
                    ),
                    1L
            );

            try (Statement st = con.createStatement()) {
                var rs = st.executeQuery("select validade, controla_lote from produtos where id = " + produtoId);
                assertTrue(rs.next());
                assertEquals("2026-11-15", rs.getString("validade"));
                assertEquals(1, rs.getInt("controla_lote"));
            }
        }
    }

    @Test
    void shouldRegisterStockLossWithFinancialExpense() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            DesktopInventoryService service = new DesktopInventoryService(con);

            long produtoId = service.saveProduct(
                    new DesktopInventoryService.ProductDraft(
                            "Molho quebrado", null, "SKU4", "Mercearia", "un",
                            new BigDecimal("4.50"), new BigDecimal("8.00"), new BigDecimal("10"),
                            new BigDecimal("1"), "A1", null, ""
                    ),
                    1L,
                    "P99996"
            );

            DesktopInventoryService.StockLossResult result = service.registerStockLoss(
                    new DesktopInventoryService.StockLossRequest(
                            produtoId, new BigDecimal("2"), "AVARIA_QUEBRA", "Vidro quebrado no estoque"
                    ),
                    1L
            );

            assertEquals("QUEBRA", result.movimentoTipo());
            assertEquals(new BigDecimal("9.00"), result.valorPerda());

            try (Statement st = con.createStatement()) {
                var prod = st.executeQuery("select estoque_atual from produtos where id = " + produtoId);
                assertTrue(prod.next());
                assertEquals(8, prod.getBigDecimal("estoque_atual").intValue());

                var mov = st.executeQuery("select tipo, quantidade, observacao from movimentacao_estoque where produto_id = " + produtoId);
                assertTrue(mov.next());
                assertEquals("QUEBRA", mov.getString("tipo"));
                assertEquals(2, mov.getBigDecimal("quantidade").intValue());
                assertTrue(mov.getString("observacao").contains("Avaria/quebra"));

                var fin = st.executeQuery("select tipo, categoria, valor_total, valor_baixado, status, forma_baixa from financeiro_lancamentos where id = " + result.lancamentoFinanceiroId());
                assertTrue(fin.next());
                assertEquals("PAGAR", fin.getString("tipo"));
                assertEquals("Perdas de estoque - Avaria/quebra", fin.getString("categoria"));
                assertEquals(new BigDecimal("9"), fin.getBigDecimal("valor_total").stripTrailingZeros());
                assertEquals(new BigDecimal("9"), fin.getBigDecimal("valor_baixado").stripTrailingZeros());
                assertEquals("QUITADO", fin.getString("status"));
                assertEquals("BAIXA_ESTOQUE", fin.getString("forma_baixa"));
            }
        }
    }
}
