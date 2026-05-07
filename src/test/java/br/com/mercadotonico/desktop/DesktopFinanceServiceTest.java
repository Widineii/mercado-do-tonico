package br.com.mercadotonico.desktop;

import br.com.mercadotonico.db.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopFinanceServiceTest {
    @Test
    void shouldCreateAndPartiallySettleFinancialEntry() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            seedUser(con);
            DesktopFinanceService service = new DesktopFinanceService(con);

            long id = service.createEntry(new DesktopFinanceService.FinanceEntryRequest(
                    "PAGAR",
                    "Conta de luz",
                    "Energia Local",
                    "Utilidades",
                    new BigDecimal("120.00"),
                    "2026-05-10",
                    "Boleto maio"
            ), 999L);

            service.settle(id, new BigDecimal("20.00"), "DINHEIRO", "Entrada parcial");

            try (Statement st = con.createStatement()) {
                var rs = st.executeQuery("select valor_baixado, status, forma_baixa from financeiro_lancamentos where id = " + id);
                assertTrue(rs.next());
                assertEquals(new BigDecimal("20"), rs.getBigDecimal("valor_baixado"));
                assertEquals("PARCIAL", rs.getString("status"));
                assertEquals("DINHEIRO", rs.getString("forma_baixa"));
            }
        }
    }

    @Test
    void shouldSummarizeOpenAndDailyFinancialFlow() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            seedUser(con);
            DesktopFinanceService service = new DesktopFinanceService(con);

            long pagar = service.createEntry(new DesktopFinanceService.FinanceEntryRequest(
                    "PAGAR", "Fornecedor", "ABC", "Compra", new BigDecimal("80"), "2026-05-06", ""
            ), 999L);
            long receber = service.createEntry(new DesktopFinanceService.FinanceEntryRequest(
                    "RECEBER", "Servico", "Cliente XPTO", "Extra", new BigDecimal("50"), "2026-05-06", ""
            ), 999L);

            service.settle(pagar, new BigDecimal("30"), "DINHEIRO", "");
            service.settle(receber, new BigDecimal("50"), "PIX", "");

            Map<String, BigDecimal> due = service.dueSummary();
            assertEquals(new BigDecimal("50.00"), due.get("pagar_aberto"));
            assertEquals(new BigDecimal("0.00"), due.get("receber_aberto"));

            Map<String, BigDecimal> day = service.dailySettlementSummary(LocalDate.now());
            assertEquals(new BigDecimal("30.00"), day.get("contas_pagas"));
            assertEquals(new BigDecimal("50.00"), day.get("contas_recebidas"));
            assertEquals(new BigDecimal("30.00"), day.get("contas_pagas_dinheiro"));
            assertEquals(new BigDecimal("0.00"), day.get("contas_recebidas_dinheiro"));
        }
    }

    private void seedUser(Connection con) throws Exception {
        try (Statement st = con.createStatement()) {
            st.executeUpdate("insert into usuarios (id, nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero) values (999, 'Admin', 'admin_fin', 'x', 'ADMIN', 1, 30, 1)");
        }
    }
}
