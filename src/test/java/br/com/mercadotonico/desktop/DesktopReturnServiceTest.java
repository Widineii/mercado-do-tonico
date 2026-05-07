package br.com.mercadotonico.desktop;

import br.com.mercadotonico.db.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopReturnServiceTest {
    @Test
    void shouldProcessPartialCashReturnAndRestockProduct() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            seedSale(con);

            DesktopReturnService service = new DesktopReturnService(con);
            DesktopReturnService.ReturnableItem item = service.listReturnableItems(100L).get(0);

            DesktopReturnService.ReturnResult result = service.processReturn(
                    new DesktopReturnService.ReturnRequest(
                            100L,
                            1L,
                            "DEVOLUCAO",
                            "DINHEIRO",
                            "Cliente desistiu",
                            List.of(new DesktopReturnService.ReturnItemRequest(item.vendaItemId(), new BigDecimal("2")))
                    ),
                    10L
            );

            assertEquals(new BigDecimal("12.00"), result.valorTotal());
            try (Statement st = con.createStatement()) {
                var produto = st.executeQuery("select estoque_atual from produtos where id = 999");
                assertTrue(produto.next());
                assertEquals(new BigDecimal("7"), produto.getBigDecimal("estoque_atual"));

                var caixa = st.executeQuery("select valor from caixa_operacoes where tipo = 'DEVOLUCAO_DINHEIRO'");
                assertTrue(caixa.next());
                assertEquals(new BigDecimal("12"), caixa.getBigDecimal("valor"));
            }
        }
    }

    @Test
    void shouldGenerateStoreCreditForExchangeAndConsumeIt() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            seedSale(con);

            DesktopReturnService service = new DesktopReturnService(con);
            DesktopReturnService.ReturnableItem item = service.listReturnableItems(100L).get(0);

            DesktopReturnService.ReturnResult result = service.processReturn(
                    new DesktopReturnService.ReturnRequest(
                            100L,
                            1L,
                            "TROCA",
                            "VALE_TROCA",
                            "Troca por outro produto",
                            List.of(new DesktopReturnService.ReturnItemRequest(item.vendaItemId(), BigDecimal.ONE))
                    ),
                    10L
            );

            assertNotNull(result.codigoCredito());
            assertEquals("VALE_TROCA", result.formaDestino());
            service.consumeStoreCredit(result.codigoCredito(), new BigDecimal("6.00"), 20L);

            try (Statement st = con.createStatement()) {
                var credito = st.executeQuery("select saldo, status from creditos_troca");
                assertTrue(credito.next());
                assertEquals(BigDecimal.ZERO.setScale(2), credito.getBigDecimal("saldo").setScale(2));
                assertEquals("UTILIZADO", credito.getString("status"));
            }
        }
    }

    private void seedSale(Connection con) throws Exception {
        try (Statement st = con.createStatement()) {
            st.executeUpdate("insert into usuarios (id, nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero) values (10, 'Caixa', 'cx', 'x', 'CAIXA', 1, 5, 0)");
            st.executeUpdate("insert into clientes (id, nome, cpf, telefone, endereco, limite_credito, bloqueado) values (20, 'Cliente Teste', '12345678901', '11999999999', 'Rua A', 100, 0)");
            st.executeUpdate("insert into produtos (id, nome, codigo_barras, sku, categoria, unidade, preco_custo, preco_venda, estoque_atual, estoque_minimo, ativo) values (999, 'Produto A', '7000000000999', 'SKU-A', 'Mercearia', 'un', 3, 6, 5, 1, 1)");
            st.executeUpdate("update caixas set abertura_valor = 100, abertura_timestamp = '2026-05-06T08:00:00', status = 'ABERTO', operador_atual_id = 10 where id = 1");
            st.executeUpdate("insert into vendas (id, caixa_id, operador_id, cliente_id, total, desconto, forma_pagamento, timestamp, status) values (100, 1, 10, 20, 18, 0, 'DINHEIRO', '2026-05-06T10:00:00', 'CONCLUIDA')");
            st.executeUpdate("insert into venda_itens (id, venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (1000, 100, 999, 3, 6, 3)");
        }
    }
}
