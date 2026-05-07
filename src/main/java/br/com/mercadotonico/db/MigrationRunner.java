package br.com.mercadotonico.db;

import br.com.mercadotonico.core.SupportLogger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MigrationRunner {
    private static final List<String> MIGRATIONS = List.of(
            "db/migration/V001__baseline.sql",
            "db/migration/V002__security_and_operations.sql",
            "db/migration/V003__inventory_workflows.sql",
            "db/migration/V004__payment_flow_hardening.sql",
            "db/migration/V005__returns_and_store_credit.sql",
            "db/migration/V006__finance_accounts.sql",
            "db/migration/V007__receipts_and_lot_indexes.sql",
            "db/migration/V008__expand_user_roles.sql",
            "db/migration/V009__force_password_change.sql"
    );

    public void migrate(Connection connection) throws Exception {
        ensureMigrationTable(connection);
        repairLegacyUsuarioRoleIfNeeded(connection);
        for (String migration : MIGRATIONS) {
            if (!alreadyApplied(connection, migration)) {
                applyMigration(connection, migration);
            }
        }
        repairLegacyUsuarioRoleIfNeeded(connection);
    }

    /**
     * Bancos criados por versões antigas (ou Spring com 001 legado) podem manter
     * {@code CHECK (role IN ('ADMIN','OPERADOR'))} mesmo com V008 marcada — recria a tabela.
     */
    void repairLegacyUsuarioRoleIfNeeded(Connection connection) throws Exception {
        String ddl = null;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("select sql from sqlite_master where type = 'table' and name = 'usuarios'")) {
            if (rs.next()) {
                ddl = rs.getString(1);
            }
        }
        if (ddl == null || !needsLegacyRoleRepair(ddl)) {
            return;
        }
        SupportLogger.log("INFO", "migration", "Reparando CHECK legado em usuarios.role", "");

        List<String> columns = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("pragma table_info(usuarios)")) {
            while (rs.next()) {
                columns.add(rs.getString("name").toLowerCase(Locale.ROOT));
            }
        }

        String roleExpr = "CASE WHEN role = 'OPERADOR' THEN 'CAIXA' ELSE role END";
        String pinExpr = columns.contains("pin_hash") ? "pin_hash" : "null";
        String ativoExpr = columns.contains("ativo") ? "coalesce(ativo, 1)" : "1";
        String dmExpr = columns.contains("desconto_maximo") ? "coalesce(desconto_maximo, 0)" : "0";
        String apzExpr = columns.contains("autoriza_preco_zero") ? "coalesce(autoriza_preco_zero, 0)" : "0";

        String insertSql = """
                insert into usuarios (id, nome, login, senha_hash, role, pin_hash, ativo, desconto_maximo, autoriza_preco_zero)
                select id, nome, login, senha_hash, %s, %s, %s, %s, %s
                from usuarios_legacy_role_fix
                """.formatted(roleExpr, pinExpr, ativoExpr, dmExpr, apzExpr);

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            st.execute("pragma foreign_keys = off");
            st.execute("alter table usuarios rename to usuarios_legacy_role_fix");
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
            st.execute(insertSql);
            st.execute("drop table usuarios_legacy_role_fix");
            st.execute("pragma foreign_keys = on");
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            SupportLogger.log("ERROR", "migration", "Falha ao reparar usuarios.role", e.getMessage());
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static boolean needsLegacyRoleRepair(String ddl) {
        String u = ddl.toUpperCase(Locale.ROOT);
        return u.contains("CHECK") && u.contains("'OPERADOR'");
    }

    private void ensureMigrationTable(Connection connection) throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                create table if not exists schema_migrations (
                  id integer primary key autoincrement,
                  arquivo text not null unique,
                  aplicado_em text not null default current_timestamp
                )
                """);
        }
    }

    private boolean alreadyApplied(Connection connection, String migration) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("select 1 from schema_migrations where arquivo = ?")) {
            ps.setString(1, migration);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        }
    }

    private void applyMigration(Connection connection, String migration) throws Exception {
        SupportLogger.log("INFO", "migration", "Aplicando migration", migration);
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(migration)) {
            if (in == null) {
                throw new IllegalStateException("Migration nao encontrada: " + migration);
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String part : sql.split(";")) {
                String stmt = part.trim();
                if (!stmt.isBlank() && !stmt.startsWith("--")) {
                    try (Statement st = connection.createStatement()) {
                        try {
                            st.execute(stmt);
                        } catch (Exception stmtError) {
                            if (isIgnorableSqliteDuplicateColumn(stmtError)) {
                                SupportLogger.log("WARN", "migration", "Coluna ja existente ignorada", stmt);
                                continue;
                            }
                            throw stmtError;
                        }
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("insert into schema_migrations (arquivo) values (?)")) {
                ps.setString(1, migration);
                ps.executeUpdate();
            }
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            SupportLogger.log("ERROR", "migration", "Falha ao aplicar migration", migration + " :: " + e.getMessage());
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private boolean isIgnorableSqliteDuplicateColumn(Exception error) {
        String msg = error.getMessage();
        if (msg == null) {
            return false;
        }
        String normalized = msg.toLowerCase(Locale.ROOT);
        return normalized.contains("duplicate column name");
    }
}
