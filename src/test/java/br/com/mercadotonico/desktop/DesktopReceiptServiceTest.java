package br.com.mercadotonico.desktop;

import br.com.mercadotonico.db.MigrationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopReceiptServiceTest {
    @Test
    void shouldGenerateTxtAndPdfReceipt(@TempDir Path tempDir) throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            seedSale(con);

            DesktopReceiptService service = new DesktopReceiptService(con, tempDir.toFile());
            DesktopReceiptService.ReceiptFiles files = service.generateForSale(100L);

            assertTrue(files.txtFile().isFile());
            assertTrue(files.pdfFile().isFile());
            assertTrue(files.txtFile().length() > 0);
            assertTrue(files.pdfFile().length() > 0);
        }
    }

    private void seedSale(Connection con) throws Exception {
        try (Statement st = con.createStatement()) {
            st.executeUpdate("insert into usuarios (id, nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero) values (888, 'Caixa', 'cx_receipt', 'x', 'CAIXA', 1, 5, 0)");
            st.executeUpdate("insert into produtos (id, nome, codigo_barras, sku, categoria, unidade, preco_custo, preco_venda, estoque_atual, estoque_minimo, ativo) values (887, 'Produto Cupom', '7000000000887', 'SKU-CUPOM', 'Mercearia', 'un', 3, 7, 10, 1, 1)");
            st.executeUpdate("update caixas set abertura_valor = 50, abertura_timestamp = '2026-05-06T08:00:00', status = 'ABERTO', operador_atual_id = 888 where id = 1");
            st.executeUpdate("insert into vendas (id, caixa_id, operador_id, total, desconto, forma_pagamento, timestamp, status) values (100, 1, 888, 14, 0, 'DINHEIRO', '2026-05-06T10:00:00', 'CONCLUIDA')");
            st.executeUpdate("insert into venda_itens (id, venda_id, produto_id, quantidade, preco_unitario, custo_unitario) values (200, 100, 887, 2, 7, 3)");
            st.executeUpdate("insert into venda_pagamentos (venda_id, forma, valor) values (100, 'DINHEIRO', 14)");
        }
    }
}
