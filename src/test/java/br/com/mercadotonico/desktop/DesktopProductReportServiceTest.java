package br.com.mercadotonico.desktop;

import br.com.mercadotonico.db.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopProductReportServiceTest {

    @Test
    void rankingByQuantityOrdersByUnits() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            seedUsuarioEVenda(con, 1, "2026-05-10T12:00:00");
            try (Statement st = con.createStatement()) {
                st.executeUpdate("insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (500, 1, 2, 10, 5)");
                st.executeUpdate("insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (500, 2, 10, 1, 0.5)");
            }
            DesktopProductReportService svc = new DesktopProductReportService(con);
            List<Map<String, Object>> rows = svc.rankingByQuantity(LocalDate.of(2026, 5, 10), null, 20);
            assertEquals(2, rows.size());
            assertEquals("Feijao Carioca 1kg", rows.get(0).get("Produto"));
            assertEquals(0, new BigDecimal("10").compareTo(toBd(rows.get(0).get("Quantidade"))));
            assertEquals("Arroz Tipo 1 5kg", rows.get(1).get("Produto"));
        }
    }

    @Test
    void rankingByRevenueOrdersByFat() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            seedUsuarioEVenda(con, 1, "2026-05-10T12:00:00");
            try (Statement st = con.createStatement()) {
                st.executeUpdate("insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (500, 1, 1, 100, 10)");
                st.executeUpdate("insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (500, 2, 50, 1, 0.5)");
            }
            DesktopProductReportService svc = new DesktopProductReportService(con);
            List<Map<String, Object>> rows = svc.rankingByRevenue(LocalDate.of(2026, 5, 10), null, 20);
            assertEquals(2, rows.size());
            assertEquals("Arroz Tipo 1 5kg", rows.get(0).get("Produto"));
            assertEquals(0, new BigDecimal("100").compareTo(toBd(rows.get(0).get("Faturamento"))));
        }
    }

    @Test
    void abcByRevenueClassicClasses() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            seedUsuarioEVenda(con, 1, "2026-05-10T12:00:00");
            try (Statement st = con.createStatement()) {
                st.executeUpdate("insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (500, 1, 1, 800, 1)");
                st.executeUpdate("insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (500, 2, 1, 150, 1)");
                st.executeUpdate("insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (500, 3, 1, 50, 1)");
            }
            DesktopProductReportService svc = new DesktopProductReportService(con);
            List<Map<String, Object>> rows = svc.abcByRevenue(LocalDate.of(2026, 5, 10), null);
            assertEquals(3, rows.size());
            assertEquals("A", rows.get(0).get("Classe"));
            assertEquals(0, new BigDecimal("80").compareTo(toBd(rows.get(0).get("PctAcumulado"))));
            assertEquals("B", rows.get(1).get("Classe"));
            assertEquals(0, new BigDecimal("95").compareTo(toBd(rows.get(1).get("PctAcumulado"))));
            assertEquals("C", rows.get(2).get("Classe"));
            assertEquals(0, new BigDecimal("100").compareTo(toBd(rows.get(2).get("PctAcumulado"))));
        }
    }

    @Test
    void singleProductIsClassA() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            seedUsuarioEVenda(con, 1, "2026-05-10T12:00:00");
            try (Statement st = con.createStatement()) {
                st.executeUpdate("insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (500, 1, 1, 50, 1)");
            }
            DesktopProductReportService svc = new DesktopProductReportService(con);
            List<Map<String, Object>> rows = svc.abcByRevenue(LocalDate.of(2026, 5, 10), null);
            assertEquals(1, rows.size());
            assertEquals("A", rows.get(0).get("Classe"));
        }
    }

    @Test
    void caixaFilterExcludesOtherCaixa() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            seedUsuarioEVenda(con, 1, "2026-05-10T10:00:00");
            try (Statement st = con.createStatement()) {
                st.executeUpdate("insert into vendas (id, caixa_id, operador_id, total, desconto, forma_pagamento, timestamp, status) "
                        + "values (501, 2, 10, 10, 0, 'DINHEIRO', '2026-05-10T11:00:00', 'CONCLUIDA')");
                st.executeUpdate("insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (500, 1, 5, 1, 0)");
                st.executeUpdate("insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (501, 2, 99, 1, 0)");
            }
            DesktopProductReportService svc = new DesktopProductReportService(con);
            List<Map<String, Object>> cx1 = svc.rankingByQuantity(LocalDate.of(2026, 5, 10), 1L, 20);
            assertEquals(1, cx1.size());
            assertTrue(cx1.get(0).get("Produto").toString().contains("Arroz"));
        }
    }

    private static void seedUsuarioEVenda(Connection con, long caixaId, String ts) throws Exception {
        try (Statement st = con.createStatement()) {
            st.executeUpdate("insert into usuarios (id, nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero) "
                    + "values (10, 'Caixa', 'cx', 'x', 'CAIXA', 1, 5, 0)");
            st.executeUpdate("insert into vendas (id, caixa_id, operador_id, total, desconto, forma_pagamento, timestamp, status) "
                    + "values (500, " + caixaId + ", 10, 100, 0, 'DINHEIRO', '" + ts + "', 'CONCLUIDA')");
        }
    }

    private static BigDecimal toBd(Object o) {
        return new BigDecimal(o.toString()).stripTrailingZeros();
    }
}
