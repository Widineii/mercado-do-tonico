package br.com.mercadotonico.db;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationRunnerTest {
    @Test
    void shouldApplyAllConfiguredMigrations() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            MigrationRunner runner = new MigrationRunner();
            runner.migrate(con);

            try (Statement st = con.createStatement()) {
                ResultSet rs = st.executeQuery("select count(*) from schema_migrations");
                assertTrue(rs.next());
                assertEquals(9, rs.getInt(1));

                ResultSet roleCol = st.executeQuery("pragma table_info(usuarios)");
                boolean foundDiscount = false;
                boolean foundTemporaryPassword = false;
                while (roleCol.next()) {
                    if ("desconto_maximo".equalsIgnoreCase(roleCol.getString("name"))) {
                        foundDiscount = true;
                    }
                    if ("senha_temporaria".equalsIgnoreCase(roleCol.getString("name"))) {
                        foundTemporaryPassword = true;
                    }
                }
                assertTrue(foundDiscount);
                assertTrue(foundTemporaryPassword);

                ResultSet roleCheck = st.executeQuery("select sql from sqlite_master where type = 'table' and name = 'usuarios'");
                assertTrue(roleCheck.next());
                assertTrue(roleCheck.getString(1).contains("'GERENTE'"));
                assertTrue(roleCheck.getString(1).contains("'CAIXA'"));
                assertTrue(roleCheck.getString(1).contains("'ESTOQUE'"));
            }
        }
    }

    @Test
    void shouldRepairLegacyUsuarioOperadorConstraint() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = con.createStatement()) {
                st.execute("""
                        create table usuarios (
                          id integer primary key autoincrement,
                          nome text not null,
                          login text not null unique,
                          senha_hash text not null,
                          role text not null check (role in ('ADMIN','OPERADOR')),
                          pin_hash text,
                          ativo integer not null default 1,
                          desconto_maximo numeric not null default 0,
                          autoriza_preco_zero integer not null default 0
                        )""");
                st.execute("""
                        insert into usuarios (nome, login, senha_hash, role, pin_hash, ativo)
                        values ('Adm','admin','x','ADMIN',null,1)
                        """);
            }
            new MigrationRunner().repairLegacyUsuarioRoleIfNeeded(con);
            try (Statement st = con.createStatement()) {
                st.execute("""
                        insert into usuarios (nome, login, senha_hash, role, ativo, desconto_maximo, autoriza_preco_zero)
                        values ('Gerente','gerente','y','GERENTE',1,15,0)
                        """);
            }
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("select sql from sqlite_master where name = 'usuarios'")) {
                assertTrue(rs.next());
                String sqlDef = rs.getString(1);
                assertTrue(sqlDef.contains("'GERENTE'"));
                assertTrue(sqlDef.contains("'CAIXA'"));
            }
        }
    }
}
