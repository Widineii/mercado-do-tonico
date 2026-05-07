package br.com.mercadotonico.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FornecedorLookupServiceTest {
    @Test
    void shouldFindSupplierLocallyByNormalizedCnpj() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(con, true));
            jdbc.execute("""
                    create table fornecedores (
                      id integer primary key autoincrement,
                      razao_social text not null,
                      nome_fantasia text,
                      cnpj text unique,
                      telefone text,
                      email text,
                      endereco text,
                      contato text
                    )
                    """);
            jdbc.update("""
                    insert into fornecedores (razao_social, nome_fantasia, cnpj, telefone, email, endereco, contato)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, "Fornecedor Teste", "Teste", "11.222.333/0001-81", "11999999999",
                    "teste@mercado.local", "Rua A", "Marcia");

            FornecedorLookupService service = new FornecedorLookupService(jdbc, false, 1);
            var fornecedor = service.buscarPorCnpj("11222333000181");

            assertTrue(fornecedor.isPresent());
            assertEquals("Fornecedor Teste", fornecedor.get().get("razao_social"));
            assertEquals("11.222.333/0001-81", fornecedor.get().get("cnpj"));
        }
    }
}
