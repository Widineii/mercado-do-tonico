package br.com.mercadotonico.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class CadastroService {
    private final JdbcTemplate jdbc;

    public CadastroService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> fornecedores() {
        return jdbc.queryForList("select * from fornecedores order by razao_social");
    }

    public List<Map<String, Object>> clientes() {
        return jdbc.queryForList("""
            select c.*, coalesce(sum(f.valor - f.valor_pago),0) as divida
            from clientes c left join fiado f on f.cliente_id = c.id and f.status = 'ABERTO'
            group by c.id order by c.nome
            """);
    }

    public void salvarFornecedor(Map<String, String> f) {
        jdbc.update("""
            insert into fornecedores (razao_social, nome_fantasia, cnpj, telefone, email, endereco, contato)
            values (?, ?, ?, ?, ?, ?, ?)
            on conflict(cnpj) do update set razao_social=excluded.razao_social, nome_fantasia=excluded.nome_fantasia,
            telefone=excluded.telefone, email=excluded.email, endereco=excluded.endereco, contato=excluded.contato
            """, f.get("razao_social"), f.get("nome_fantasia"), normalizeCnpj(f.get("cnpj")), f.get("telefone"), f.get("email"),
                f.get("endereco"), f.get("contato"));
    }

    public void salvarCliente(Map<String, String> f) {
        jdbc.update("""
            insert into clientes (nome, cpf, telefone, endereco, limite_credito, observacoes, bloqueado)
            values (?, ?, ?, ?, ?, ?, ?)
            on conflict(cpf) do update set nome=excluded.nome, telefone=excluded.telefone, endereco=excluded.endereco,
            limite_credito=excluded.limite_credito, observacoes=excluded.observacoes, bloqueado=excluded.bloqueado
            """, f.get("nome"), f.get("cpf"), f.get("telefone"), f.get("endereco"),
                n(f.get("limite_credito")), f.get("observacoes"), "on".equals(f.get("bloqueado")) ? 1 : 0);
    }

    public List<Map<String, Object>> fiadosAbertos() {
        return jdbc.queryForList("""
            select f.*, c.nome as cliente_nome
            from fiado f join clientes c on c.id = f.cliente_id
            where f.status = 'ABERTO'
            order by f.data_criacao desc
            """);
    }

    public void pagarFiado(long fiadoId, BigDecimal valor, long operadorId) {
        jdbc.update("insert into fiado_pagamentos (fiado_id, valor, data, operador_id) values (?, ?, datetime('now'), ?)",
                fiadoId, valor, operadorId);
        jdbc.update("update fiado set valor_pago = valor_pago + ? where id = ?", valor, fiadoId);
        jdbc.update("update fiado set status = 'PAGO' where id = ? and valor_pago >= valor", fiadoId);
    }

    private BigDecimal n(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.replace(",", "."));
    }

    private String normalizeCnpj(String cnpj) {
        if (cnpj == null) {
            return null;
        }
        String digits = cnpj.replaceAll("\\D", "");
        return digits.isBlank() ? null : digits;
    }
}
