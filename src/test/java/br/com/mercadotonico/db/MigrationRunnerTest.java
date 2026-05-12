package br.com.mercadotonico.db;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                assertEquals(16, rs.getInt(1));

                ResultSet nfCols = st.executeQuery("pragma table_info(notas_fiscais)");
                boolean hasChave = false;
                boolean hasStatus = false;
                while (nfCols.next()) {
                    if ("chave_acesso".equalsIgnoreCase(nfCols.getString("name"))) hasChave = true;
                    if ("status".equalsIgnoreCase(nfCols.getString("name"))) hasStatus = true;
                }
                assertTrue(hasChave, "V011 deve criar coluna chave_acesso");
                assertTrue(hasStatus, "V013 deve criar coluna status em notas_fiscais");

                ResultSet clienteCols = st.executeQuery("pragma table_info(clientes)");
                boolean hasFiadoDe = false;
                boolean hasConvenio = false;
                while (clienteCols.next()) {
                    String name = clienteCols.getString("name");
                    if ("fiado_de".equalsIgnoreCase(name)) hasFiadoDe = true;
                    if ("convenio".equalsIgnoreCase(name)) hasConvenio = true;
                }
                assertTrue(hasFiadoDe, "V012 deve criar coluna fiado_de em clientes");
                assertTrue(hasConvenio, "V012 deve criar coluna convenio em clientes");

                ResultSet produtoCols = st.executeQuery("pragma table_info(produtos)");
                boolean hasMarca = false;
                boolean hasNcm = false;
                boolean hasImagemUrl = false;
                while (produtoCols.next()) {
                    String name = produtoCols.getString("name");
                    if ("marca".equalsIgnoreCase(name)) hasMarca = true;
                    if ("ncm".equalsIgnoreCase(name)) hasNcm = true;
                    if ("imagem_url".equalsIgnoreCase(name)) hasImagemUrl = true;
                }
                assertTrue(hasMarca, "Migration V010 deve criar coluna marca");
                assertTrue(hasNcm, "Migration V010 deve criar coluna ncm");
                assertTrue(hasImagemUrl, "Migration V010 deve criar coluna imagem_url");

                ResultSet cacheTbl = st.executeQuery(
                        "select count(*) from sqlite_master where type='table' and name='produto_lookup_cache'");
                assertTrue(cacheTbl.next());
                assertEquals(1, cacheTbl.getInt(1),
                        "Migration V010 deve criar tabela produto_lookup_cache");

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

    @Test
    void shouldRepairForeignKeyReferencesPointingToLegacyUsuariosTable() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = con.createStatement()) {
                st.execute("pragma foreign_keys = off");
                st.execute("""
                        create table usuarios (
                          id integer primary key autoincrement,
                          nome text not null,
                          login text not null unique,
                          senha_hash text not null,
                          role text not null check (role in ('ADMIN','GERENTE','CAIXA','ESTOQUE')),
                          pin_hash text,
                          ativo integer not null default 1,
                          desconto_maximo numeric not null default 0,
                          autoriza_preco_zero integer not null default 0
                        )""");
                st.execute("""
                        create table caixas (
                          id integer primary key autoincrement,
                          numero integer not null unique,
                          status text not null default 'FECHADO',
                          operador_atual_id integer,
                          foreign key (operador_atual_id) references usuarios_legacy_role_fix(id)
                        )""");
                st.execute("insert into usuarios (id, nome, login, senha_hash, role) values (1, 'Admin', 'admin', 'x', 'ADMIN')");
                st.execute("insert into caixas (numero, status, operador_atual_id) values (1, 'ABERTO', 1)");
                st.execute("pragma foreign_keys = on");
            }
            MigrationRunner runner = new MigrationRunner();
            runner.repairLegacyForeignKeyReferences(con);
            try (Statement st = con.createStatement()) {
                ResultSet rs = st.executeQuery("select sql from sqlite_master where type = 'table' and name = 'caixas'");
                assertTrue(rs.next());
                assertTrue(rs.getString(1).contains("references usuarios(id)"));
                st.execute("update caixas set operador_atual_id = 1 where id = 1");
            }
        }
    }

    @Test
    void shouldSanitizeLegacyFkFixReferencesInDdl() {
        String ddl = "create table x (a int references vendas_legacy_fk_fix(id))";
        assertFalse(MigrationRunner.sanitizeLegacyFkFixDdl(ddl).contains("_legacy_fk_fix"));
    }

    @Test
    void shouldScrubLegacyFkFixSuffixesGlobally() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = con.createStatement()) {
                st.execute("pragma foreign_keys = off");
                st.execute("create table caixas (id integer primary key, numero integer)");
                st.execute("create table usuarios (id integer primary key, nome text)");
                st.execute("insert into caixas values (1, 1)");
                st.execute("insert into usuarios values (1, 'a')");
                st.execute("""
                        create table vendas (
                          id integer primary key autoincrement,
                          caixa_id integer not null references caixas(id),
                          operador_id integer not null references usuarios(id),
                          total numeric not null
                        )""");
                st.execute("""
                        create table venda_itens (
                          id integer primary key autoincrement,
                          venda_id integer not null references vendas_legacy_fk_fix(id),
                          produto_id integer not null,
                          quantidade numeric not null,
                          preco_unitario numeric not null
                        )""");
                st.execute("""
                        insert into vendas (caixa_id, operador_id, total) values (1, 1, 10)
                        """);
                st.execute("pragma foreign_keys = on");
            }
            new MigrationRunner().scrubLegacyFkFixSuffixesGlobally(con);
            try (Statement st = con.createStatement()) {
                ResultSet rs = st.executeQuery("""
                        select count(*) from sqlite_master where instr(coalesce(sql, ''), '_legacy_fk_fix') > 0
                        """);
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
                st.execute("""
                        insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario)
                        values (1, 1, 1, 5)
                        """);
            }
        }
    }
}
