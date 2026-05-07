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
}
