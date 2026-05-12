package br.com.mercadotonico.db;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaUsuariosOldProbeTest {

    @Test
    void afterMigrate_sqliteMaster_shouldNotReferenceUsuariosOld() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new MigrationRunner().migrate(con);
            List<String> hits = new ArrayList<>();
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select type || ':' || name as n, sql from sqlite_master where instr(coalesce(sql,''), 'usuarios_old') > 0")) {
                while (rs.next()) {
                    hits.add(rs.getString(1) + " -> " + rs.getString(2));
                }
            }
            assertTrue(hits.isEmpty(), "Objetos com DDL contendo usuarios_old: " + hits);
        }
    }
}
