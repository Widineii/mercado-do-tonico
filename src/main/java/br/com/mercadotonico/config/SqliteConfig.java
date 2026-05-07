package br.com.mercadotonico.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.File;

@Configuration
public class SqliteConfig {
    private final Environment env;

    public SqliteConfig(Environment env) {
        this.env = env;
    }

    @Bean
    DataSource dataSource() {
        new File("data").mkdirs();
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        String dbUrl = System.getenv("MERCADO_DB_URL");
        if (dbUrl == null || dbUrl.isBlank()) {
            dbUrl = env.getProperty("spring.datasource.url", "jdbc:sqlite:data/mercado-tonico.db");
        }
        ds.setUrl(dbUrl);
        return ds;
    }
}
