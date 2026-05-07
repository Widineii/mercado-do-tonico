package br.com.mercadotonico.service;

import br.com.mercadotonico.core.AppException;
import br.com.mercadotonico.db.MigrationRunner;
import br.com.mercadotonico.model.UsuarioLogado;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VendaServiceTest {
    @Test
    void shouldBlockSaleWhenStockIsInsufficient() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(con, true));
            jdbc.update("""
                    insert into usuarios (id, nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero)
                    values (10, 'Caixa', 'cx_web', 'x', 'CAIXA', 1, 5, 0)
                    """);
            jdbc.update("""
                    insert into produtos (id, nome, codigo_barras, sku, categoria, unidade, preco_custo, preco_venda, estoque_atual, estoque_minimo, ativo)
                    values (20, 'Produto Web', '7890000000020', 'SKU-WEB', 'Mercearia', 'un', 3, 5, 1, 1, 1)
                    """);

            VendaService service = new VendaService(jdbc);
            AppException error = assertThrows(AppException.class, () -> service.finalizar(
                    1L,
                    null,
                    "DINHEIRO",
                    BigDecimal.ZERO,
                    List.of(Map.of(
                            "produto_id", "20",
                            "quantidade", "2",
                            "preco_unitario", "5.00"
                    )),
                    List.of(Map.of("forma", "DINHEIRO", "valor", "10.00")),
                    new UsuarioLogado(10L, "Caixa", "cx_web", "CAIXA")
            ));

            assertEquals("Estoque insuficiente para Produto Web.", error.getMessage());
        }
    }
}
