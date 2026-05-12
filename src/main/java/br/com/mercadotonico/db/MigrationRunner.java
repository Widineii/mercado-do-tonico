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
import java.util.regex.Pattern;

public class MigrationRunner {
    private static final Pattern REFERENCES_LEGACY_FK_FIX = Pattern.compile(
            "(?i)(REFERENCES\\s+)([a-zA-Z0-9_]+)_legacy_fk_fix");

    private static final List<String> MIGRATIONS = List.of(
            "db/migration/V001__baseline.sql",
            "db/migration/V002__security_and_operations.sql",
            "db/migration/V003__inventory_workflows.sql",
            "db/migration/V004__payment_flow_hardening.sql",
            "db/migration/V005__returns_and_store_credit.sql",
            "db/migration/V006__finance_accounts.sql",
            "db/migration/V007__receipts_and_lot_indexes.sql",
            "db/migration/V008__expand_user_roles.sql",
            "db/migration/V009__force_password_change.sql",
            "db/migration/V010__product_barcode_lookup.sql",
            "db/migration/V011__nota_fiscal_chave_acesso.sql",
            "db/migration/V012__cliente_convenio_fiado_de.sql",
            "db/migration/V013__notas_fiscais_status_pendente_baixa.sql",
            "db/migration/V014__fornecedores_dados_completos.sql",
            "db/migration/V015__fornecedores_endereco_tipo_ativo.sql",
            "db/migration/V016__financeiro_nota_fiscal.sql"
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
        repairLegacyForeignKeyReferences(connection);
        scrubLegacyFkFixSuffixesGlobally(connection);
        dropOrphanLegacyFkObjects(connection);
        repairUsuariosOldReferencesInSqliteMaster(connection);
    }

    /**
     * A migracao V008 renomeia {@code usuarios} para {@code usuarios_old} com
     * {@code PRAGMA foreign_keys = OFF}. Ao dropar {@code usuarios_old}, o SQLite pode
     * deixar DDLs em {@code sqlite_master} ainda referenciando {@code usuarios_old},
     * quebrando INSERTs com FK (ex.: {@code financeiro_lancamentos.criado_por}).
     * Substitui o identificador remanescente por {@code usuarios} em todo o texto da DDL.
     */
    void repairUsuariosOldReferencesInSqliteMaster(Connection connection) throws Exception {
        int polluted;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("""
                     select count(*) from sqlite_master
                     where instr(coalesce(sql, ''), 'usuarios_old') > 0
                     """)) {
            polluted = rs.next() ? rs.getInt(1) : 0;
        }
        if (polluted == 0) {
            return;
        }
        SupportLogger.log("WARN", "migration", "Corrigindo DDLs que ainda referenciam usuarios_old", "linhas=" + polluted);
        boolean autoCommit = connection.getAutoCommit();
        try (Statement st = connection.createStatement()) {
            st.execute("pragma writable_schema = ON");
            connection.setAutoCommit(true);
            try {
                st.executeUpdate("""
                        update sqlite_master
                           set sql = replace(coalesce(sql, ''), 'usuarios_old', 'usuarios')
                         where instr(coalesce(sql, ''), 'usuarios_old') > 0
                        """);
            } finally {
                st.execute("pragma writable_schema = OFF");
            }
            st.execute("vacuum");
        } catch (Exception e) {
            SupportLogger.log("ERROR", "migration", "Falha ao corrigir referencias a usuarios_old", e.getMessage());
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("""
                     select count(*) from sqlite_master
                     where instr(coalesce(sql, ''), 'usuarios_old') > 0
                     """)) {
            int remaining = rs.next() ? rs.getInt(1) : 0;
            SupportLogger.log("INFO", "migration", "Pos-correcao usuarios_old", "remanescente=" + remaining);
        }
    }

    /**
     * Remove globalmente o sufixo {@code _legacy_fk_fix} de toda a definicao de schema
     * (DDLs em {@code sqlite_master}). Quando uma migracao antiga renomeou tabelas como
     * {@code vendas} para {@code vendas_legacy_fk_fix} e nao reverteu, sobram referencias
     * a {@code <tabela>_legacy_fk_fix} em FKs, indices, gatilhos e views que NUNCA mais
     * existirao. Tentar prepara um insert/update nessas tabelas falha em SQLite com
     * {@code no such table}. Esta rotina:
     *
     * <ol>
     *   <li>habilita {@code pragma writable_schema=on};</li>
     *   <li>roda {@code UPDATE sqlite_master SET sql = REPLACE(sql, '_legacy_fk_fix', '')}
     *       em qualquer linha que contenha o sufixo;</li>
     *   <li>fecha {@code writable_schema} e dispara {@code VACUUM} para reconstruir o arquivo,
     *       forcando todas as conexoes a re-parsearem o schema na proxima query.</li>
     * </ol>
     *
     * Como {@code REPLACE} apenas remove o substring, e seguro: nunca corrompe nomes de
     * tabelas reais (que nao terminam com esse sufixo) e converte
     * {@code references vendas_legacy_fk_fix(id)} em {@code references vendas(id)}.
     */
    void scrubLegacyFkFixSuffixesGlobally(Connection connection) throws Exception {
        int polluted;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("""
                     select count(*) from sqlite_master
                     where instr(coalesce(sql, ''), '_legacy_fk_fix') > 0
                     """)) {
            polluted = rs.next() ? rs.getInt(1) : 0;
        }
        if (polluted == 0) {
            return;
        }
        SupportLogger.log("WARN", "migration", "Limpando _legacy_fk_fix do sqlite_master", "linhas=" + polluted);
        boolean autoCommit = connection.getAutoCommit();
        try (Statement st = connection.createStatement()) {
            st.execute("pragma writable_schema = ON");
            connection.setAutoCommit(true);
            try {
                st.executeUpdate("""
                        update sqlite_master
                           set sql = replace(sql, '_legacy_fk_fix', '')
                         where instr(coalesce(sql, ''), '_legacy_fk_fix') > 0
                        """);
            } finally {
                st.execute("pragma writable_schema = OFF");
            }
            // VACUUM reconstroi o arquivo a partir das DDLs corrigidas e re-parseia o schema.
            st.execute("vacuum");
        } catch (Exception e) {
            SupportLogger.log("ERROR", "migration", "Falha ao limpar _legacy_fk_fix", e.getMessage());
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("""
                     select count(*) from sqlite_master
                     where instr(coalesce(sql, ''), '_legacy_fk_fix') > 0
                     """)) {
            int remaining = rs.next() ? rs.getInt(1) : 0;
            SupportLogger.log("INFO", "migration", "Pos-limpeza _legacy_fk_fix", "remanescente=" + remaining);
        }
    }

    static String sanitizeLegacyFkFixDdl(String ddl) {
        String s = ddl.replace("usuarios_legacy_role_fix", "usuarios");
        s = REFERENCES_LEGACY_FK_FIX.matcher(s).replaceAll("$1$2");
        return s.replace("_legacy_fk_fix", "");
    }

    /**
     * Limpa SOMENTE objetos AUXILIARES (triggers/indexes/views) e tabelas TEMPORARIAS
     * remanescentes do conserto de FK. Regra estrita para nao apagar nenhuma tabela real:
     *
     * <ul>
     *   <li>{@code trigger}/{@code index}/{@code view}: drop se o {@code name} ou o
     *       {@code tbl_name} terminar em {@code _legacy_fk_fix}, OU se o {@code sql}
     *       referenciar uma tabela {@code *_legacy_fk_fix}. Esses objetos sao
     *       reconstruidos via DDL pelo MigrationRunner / DesktopApp na proxima abertura,
     *       portanto e seguro recriar.</li>
     *   <li>{@code table}: drop APENAS quando o proprio {@code name} termina em
     *       {@code _legacy_fk_fix} (sao tabelas temporarias do conserto). Tabelas reais
     *       nunca sao removidas, mesmo que a DDL contenha o sufixo em algum FK.</li>
     * </ul>
     */
    void dropOrphanLegacyFkObjects(Connection connection) throws Exception {
        List<String[]> orphans = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("""
                     select type, name
                     from sqlite_master
                     where name not like 'sqlite_%'
                       and (
                             (
                               type in ('trigger','index','view')
                               and (
                                     instr(name, '_legacy_fk_fix') > 0
                                  or instr(coalesce(tbl_name, ''), '_legacy_fk_fix') > 0
                                  or instr(coalesce(sql, ''), '_legacy_fk_fix') > 0
                               )
                             )
                          or (type = 'table' and instr(name, '_legacy_fk_fix') > 0)
                       )
                     """)) {
            while (rs.next()) {
                orphans.add(new String[]{rs.getString("type"), rs.getString("name")});
            }
        }
        if (orphans.isEmpty()) {
            return;
        }
        StringBuilder dropped = new StringBuilder();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            st.execute("pragma foreign_keys = off");
            String[] order = {"trigger", "index", "view", "table"};
            for (String kind : order) {
                for (String[] obj : orphans) {
                    if (!kind.equals(obj[0])) {
                        continue;
                    }
                    String name = obj[1];
                    if ("table".equals(kind) && !name.endsWith("_legacy_fk_fix")) {
                        // proteção dupla: nunca drop em tabela real
                        continue;
                    }
                    String safeName = "\"" + name.replace("\"", "\"\"") + "\"";
                    st.execute("drop " + kind + " if exists " + safeName);
                    if (dropped.length() > 0) {
                        dropped.append(',');
                    }
                    dropped.append(kind).append(':').append(name);
                }
            }
            st.execute("pragma foreign_keys = on");
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            SupportLogger.log("ERROR", "migration", "Falha ao limpar objetos legacy_fk_fix", e.getMessage());
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        if (dropped.length() > 0) {
            SupportLogger.log("WARN", "migration", "Objetos legacy_fk_fix removidos", dropped.toString());
        }
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
                insert into usuarios_new (id, nome, login, senha_hash, role, pin_hash, ativo, desconto_maximo, autoriza_preco_zero)
                select id, nome, login, senha_hash, %s, %s, %s, %s, %s
                from usuarios
                """.formatted(roleExpr, pinExpr, ativoExpr, dmExpr, apzExpr);

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            st.execute("pragma foreign_keys = off");
            st.execute("""
                    create table usuarios_new (
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
            st.execute("drop table usuarios");
            st.execute("alter table usuarios_new rename to usuarios");
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

    void repairLegacyForeignKeyReferences(Connection connection) throws Exception {
        cleanupLegacyFkTempTables(connection);
        List<String> tables = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("""
                     select name
                     from sqlite_master
                     where type='table'
                       and name not like 'sqlite_%'
                       and sql like '%usuarios_legacy_role_fix%'
                     """)) {
            while (rs.next()) {
                tables.add(rs.getString("name"));
            }
        }
        if (tables.isEmpty()) {
            return;
        }
        SupportLogger.log("WARN", "migration", "Reparando referencias FK legadas", String.join(",", tables));
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            st.execute("pragma foreign_keys = off");
            for (String table : tables) {
                String ddl = null;
                try (PreparedStatement ps = connection.prepareStatement(
                        "select sql from sqlite_master where type='table' and name=?")) {
                    ps.setString(1, table);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        ddl = rs.getString("sql");
                    }
                }
                if (ddl == null || !ddl.contains("usuarios_legacy_role_fix")) {
                    continue;
                }
                String fixedDdl = ddl
                        .replace("usuarios_legacy_role_fix", "usuarios")
                        .replaceAll("([A-Za-z0-9_]+)_legacy_fk_fix", "$1");
                String tempTable = table + "_legacy_fk_fix";
                st.execute("alter table " + table + " rename to " + tempTable);
                st.execute(fixedDdl);
                List<String> columns = new ArrayList<>();
                try (ResultSet cols = st.executeQuery("pragma table_info(" + tempTable + ")")) {
                    while (cols.next()) {
                        columns.add(cols.getString("name"));
                    }
                }
                String cols = String.join(", ", columns);
                st.execute("insert into " + table + " (" + cols + ") select " + cols + " from " + tempTable);
                st.execute("drop table " + tempTable);
            }
            st.execute("pragma foreign_keys = on");
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            SupportLogger.log("ERROR", "migration", "Falha ao reparar referencias FK legadas", e.getMessage());
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void cleanupLegacyFkTempTables(Connection connection) throws Exception {
        List<String> tempTables = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("""
                     select name
                     from sqlite_master
                     where type='table' and name like '%_legacy_fk_fix'
                     """)) {
            while (rs.next()) {
                tempTables.add(rs.getString("name"));
            }
        }
        if (tempTables.isEmpty()) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            for (String temp : tempTables) {
                String base = temp.replaceFirst("_legacy_fk_fix$", "");
                boolean baseExists;
                try (PreparedStatement ps = connection.prepareStatement(
                        "select 1 from sqlite_master where type='table' and name=?")) {
                    ps.setString(1, base);
                    ResultSet rs = ps.executeQuery();
                    baseExists = rs.next();
                }
                if (baseExists) {
                    st.execute("drop table if exists " + temp);
                } else {
                    st.execute("alter table " + temp + " rename to " + base);
                }
            }
        }
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
            String stripped = stripSqlComments(sql);
            for (String part : stripped.split(";")) {
                String stmt = part.trim();
                if (stmt.isBlank()) continue;
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

    /**
     * Remove comentarios SQL (linha {@code -- ...} e bloco {@code /* ... *\/})
     * antes de quebrar o script por {@code ;}. Sem isso, comentarios contendo
     * ponto e virgula bagunçam o parser ingenuo de statements e geram erros
     * tipo {@code syntax error near "..."}.
     *
     * <p>Mantemos o conteudo dentro de strings ('...') intocado para nao
     * danificar valores literais.</p>
     */
    static String stripSqlComments(String sql) {
        if (sql == null || sql.isEmpty()) return "";
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0, n = sql.length();
        boolean inSingle = false;
        boolean inDouble = false;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (!inSingle && !inDouble) {
                if (c == '-' && next == '-') {
                    while (i < n && sql.charAt(i) != '\n') i++;
                    continue;
                }
                if (c == '/' && next == '*') {
                    i += 2;
                    while (i < n - 1 && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                    i = Math.min(n, i + 2);
                    continue;
                }
            }
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            out.append(c);
            i++;
        }
        return out.toString();
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
