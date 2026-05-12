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

class DesktopCashReportServiceTest {
    @Test
    void shouldSummarizeDailyClosing() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            try (Statement st = con.createStatement()) {
                st.executeUpdate("insert into usuarios (id, nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero) values (10, 'Caixa', 'cx', 'x', 'CAIXA', 1, 5, 0)");
                st.executeUpdate("update caixas set abertura_valor = 100, abertura_timestamp = '2026-05-06T08:00:00', status = 'ABERTO', operador_atual_id = 10 where id = 1");
                st.executeUpdate("insert into vendas (id, caixa_id, operador_id, total, desconto, forma_pagamento, timestamp, status) values (100, 1, 10, 80, 0, 'DINHEIRO+PIX', '2026-05-06T10:00:00', 'CONCLUIDA')");
                st.executeUpdate("insert into venda_pagamentos (venda_id, forma, valor) values (100, 'DINHEIRO', 30)");
                st.executeUpdate("insert into venda_pagamentos (venda_id, forma, valor) values (100, 'PIX', 50)");
                st.executeUpdate("insert into caixa_operacoes (caixa_id, tipo, valor, motivo, operador_id, timestamp) values (1, 'SUPRIMENTO', 20, 'Teste', 10, '2026-05-06T09:00:00')");
                st.executeUpdate("insert into caixa_operacoes (caixa_id, tipo, valor, motivo, operador_id, timestamp) values (1, 'SANGRIA', 5, 'Teste', 10, '2026-05-06T12:00:00')");
                st.executeUpdate("insert into caixa_operacoes (caixa_id, tipo, valor, motivo, operador_id, timestamp) values (1, 'DEVOLUCAO_DINHEIRO', 10, 'Teste', 10, '2026-05-06T15:00:00')");
                st.executeUpdate("insert into financeiro_lancamentos (id, tipo, descricao, parceiro, categoria, valor_total, valor_baixado, vencimento, status, forma_baixa, observacao, criado_por, criado_em, baixado_em) values (1, 'RECEBER', 'Recebimento extra', 'Cliente', 'Servicos', 25, 25, '2026-05-06', 'QUITADO', 'DINHEIRO', 'Teste', 10, '2026-05-06T08:00:00', '2026-05-06T16:00:00')");
                st.executeUpdate("insert into financeiro_lancamentos (id, tipo, descricao, parceiro, categoria, valor_total, valor_baixado, vencimento, status, forma_baixa, observacao, criado_por, criado_em, baixado_em) values (2, 'PAGAR', 'Despesa diaria', 'Fornecedor', 'Utilidades', 15, 15, '2026-05-06', 'QUITADO', 'DINHEIRO', 'Teste', 10, '2026-05-06T08:30:00', '2026-05-06T17:00:00')");
                st.executeUpdate("insert into caixa_operacoes (caixa_id, tipo, valor, motivo, operador_id, timestamp) values (1, 'FECHAMENTO', 145, 'Teste', 10, '2026-05-06T20:00:00')");
            }
            DesktopCashReportService report = new DesktopCashReportService(con);
            Map<String, BigDecimal> summary = report.dailySummary(1L, LocalDate.of(2026, 5, 6));
            assertEquals(new BigDecimal("100"), summary.get("abertura"));
            assertEquals(new BigDecimal("30"), summary.get("dinheiro"));
            assertEquals(new BigDecimal("50"), summary.get("pix"));
            assertEquals(new BigDecimal("10"), summary.get("devolucao_dinheiro"));
            assertEquals(new BigDecimal("25"), summary.get("contas_recebidas_dinheiro"));
            assertEquals(new BigDecimal("15"), summary.get("contas_pagas_dinheiro"));
            assertEquals(new BigDecimal("145"), summary.get("esperado_dinheiro"));
            assertEquals(BigDecimal.ZERO.setScale(0), summary.get("divergencia"));
        }
    }

    @Test
    void sessionSummaryUsesOpeningNotCalendarDay() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            try (Statement st = con.createStatement()) {
                st.executeUpdate("insert into usuarios (id, nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero) "
                        + "values (10, 'Caixa', 'cx', 'x', 'CAIXA', 1, 5, 0)");
                st.executeUpdate("update caixas set abertura_valor = 100, abertura_timestamp = '2026-05-06T22:00:00', "
                        + "status = 'ABERTO', operador_atual_id = 10 where id = 1");
                st.executeUpdate("insert into vendas (id, caixa_id, operador_id, total, desconto, forma_pagamento, timestamp, status) "
                        + "values (200, 1, 10, 40, 0, 'DINHEIRO', '2026-05-07T01:00:00', 'CONCLUIDA')");
                st.executeUpdate("insert into venda_pagamentos (venda_id, forma, valor) values (200, 'DINHEIRO', 40)");
            }
            DesktopCashReportService report = new DesktopCashReportService(con);
            Map<String, BigDecimal> turno = report.sessionSummarySinceOpening(1L);
            assertEquals(new BigDecimal("100"), turno.get("abertura"));
            assertEquals(new BigDecimal("40"), turno.get("dinheiro"));
            assertEquals(new BigDecimal("140"), turno.get("esperado_dinheiro"));

            Map<String, BigDecimal> dia7 = report.dailySummary(1L, LocalDate.of(2026, 5, 7));
            assertEquals(BigDecimal.ZERO, dia7.get("abertura"));
            assertEquals(new BigDecimal("40"), dia7.get("dinheiro"));
        }
    }
}
