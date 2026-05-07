package br.com.mercadotonico.service;

import br.com.mercadotonico.model.UsuarioLogado;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;

    public AuthService(JdbcTemplate jdbc, BCryptPasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    public Optional<UsuarioLogado> login(String login, String senha) {
        var rows = jdbc.queryForList("select * from usuarios where login = ? and ativo = 1", login);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var row = rows.get(0);
        if (!encoder.matches(senha, row.get("senha_hash").toString())) {
            return Optional.empty();
        }
        UsuarioLogado usuario = new UsuarioLogado(
                ((Number) row.get("id")).longValue(),
                row.get("nome").toString(),
                row.get("login").toString(),
                row.get("role").toString()
        );
        audit(usuario.id(), "LOGIN", "Entrada no sistema");
        return Optional.of(usuario);
    }

    public boolean validarPinGerente(String pin) {
        return jdbc.queryForList("select pin_hash from usuarios where role in ('ADMIN', 'GERENTE') and ativo = 1 and pin_hash is not null")
                .stream()
                .anyMatch(row -> encoder.matches(pin, row.get("pin_hash").toString()));
    }

    public void audit(Long usuarioId, String acao, String detalhe) {
        jdbc.update("insert into audit_log (usuario_id, acao, detalhe, timestamp) values (?, ?, ?, ?)",
                usuarioId, acao, detalhe, LocalDateTime.now().toString());
    }
}
