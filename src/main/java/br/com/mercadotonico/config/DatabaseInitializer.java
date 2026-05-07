package br.com.mercadotonico.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Component
public class DatabaseInitializer implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DatabaseInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) throws Exception {
        var resource = new ClassPathResource("db/migration/001_initial_schema.sql");
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isBlank() && !trimmed.startsWith("--")) {
                jdbc.execute(trimmed);
            }
        }
        seedUsers();
    }

    private void seedUsers() {
        Integer count = jdbc.queryForObject("select count(*) from usuarios", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        jdbc.update("""
            insert into usuarios (nome, login, senha_hash, role, pin_hash, ativo)
            values (?, ?, ?, ?, ?, 1)
            """, "Gerente Tonico", "admin", encoder.encode("admin123"), "ADMIN", encoder.encode("1234"));
        jdbc.update("""
            insert into usuarios (nome, login, senha_hash, role, ativo)
            values (?, ?, ?, ?, 1)
            """, "Operador Caixa 1", "caixa1", encoder.encode("caixa123"), "CAIXA");
        jdbc.update("""
            insert into usuarios (nome, login, senha_hash, role, ativo)
            values (?, ?, ?, ?, 1)
            """, "Operador Caixa 2", "caixa2", encoder.encode("caixa123"), "CAIXA");
        jdbc.update("""
            insert into usuarios (nome, login, senha_hash, role, ativo)
            values (?, ?, ?, ?, 1)
            """, "Operador Estoque", "estoque1", encoder.encode("estoque123"), "ESTOQUE");
        jdbc.update("insert into audit_log (acao, detalhe, timestamp) values (?, ?, ?)",
                "SEED", "Usuarios iniciais criados", LocalDateTime.now().toString());
    }
}
