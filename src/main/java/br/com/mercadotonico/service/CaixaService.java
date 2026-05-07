package br.com.mercadotonico.service;

import br.com.mercadotonico.model.UsuarioLogado;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class CaixaService {
    private final JdbcTemplate jdbc;
    private final AuthService auth;

    public CaixaService(JdbcTemplate jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    public List<Map<String, Object>> listar() {
        return jdbc.queryForList("""
            select c.*, u.nome as operador_nome
            from caixas c left join usuarios u on u.id = c.operador_atual_id
            order by c.numero
            """);
    }

    @Transactional
    public String abrir(int numero, BigDecimal fundo, UsuarioLogado usuario) {
        var rows = jdbc.queryForList("""
            select c.*, u.nome as operador_nome
            from caixas c left join usuarios u on u.id = c.operador_atual_id
            where c.numero = ?
            """, numero);
        if (rows.isEmpty()) {
            return "Caixa inexistente.";
        }
        var caixa = rows.get(0);
        if ("ABERTO".equals(caixa.get("status")) && ((Number) caixa.get("operador_atual_id")).longValue() != usuario.id()) {
            return "Caixa " + numero + " em uso por " + caixa.get("operador_nome") + ".";
        }
        jdbc.update("""
            update caixas set status = 'ABERTO', operador_atual_id = ?, abertura_valor = ?, abertura_timestamp = ?
            where numero = ? and (status = 'FECHADO' or operador_atual_id = ?)
            """, usuario.id(), fundo, LocalDateTime.now().toString(), numero, usuario.id());
        auth.audit(usuario.id(), "ABERTURA_CAIXA", "Caixa " + numero + " aberto com fundo " + fundo);
        return "Caixa " + numero + " aberto.";
    }

    @Transactional
    public void operacao(int caixaId, String tipo, BigDecimal valor, String motivo, UsuarioLogado usuario) {
        jdbc.update("""
            insert into caixa_operacoes (caixa_id, tipo, valor, motivo, operador_id, timestamp)
            values (?, ?, ?, ?, ?, ?)
            """, caixaId, tipo, valor, motivo, usuario.id(), LocalDateTime.now().toString());
        auth.audit(usuario.id(), tipo, motivo);
    }

    @Transactional
    public Map<String, Object> fechar(int caixaId, BigDecimal dinheiroContado, UsuarioLogado usuario) {
        Map<String, Object> resumo = resumo(caixaId);
        jdbc.update("update caixas set status = 'FECHADO', operador_atual_id = null where id = ?", caixaId);
        operacao(caixaId, "FECHAMENTO", dinheiroContado, "Dinheiro contado no fechamento", usuario);
        auth.audit(usuario.id(), "FECHAMENTO_CAIXA", "Caixa " + caixaId + " fechado");
        resumo.put("dinheiro_contado", dinheiroContado);
        resumo.put("diferenca", dinheiroContado.subtract(new BigDecimal(resumo.get("dinheiro_esperado").toString())));
        return resumo;
    }

    public Map<String, Object> resumo(int caixaId) {
        Map<String, Object> caixa = jdbc.queryForMap("select * from caixas where id = ?", caixaId);
        BigDecimal abertura = new BigDecimal(caixa.get("abertura_valor").toString());
        BigDecimal vendas = soma("select coalesce(sum(total),0) from vendas where caixa_id = ? and status = 'CONCLUIDA'", caixaId);
        BigDecimal dinheiro = soma("""
            select coalesce(sum(vp.valor),0)
            from venda_pagamentos vp join vendas v on v.id = vp.venda_id
            where v.caixa_id = ? and vp.forma = 'DINHEIRO' and v.status = 'CONCLUIDA'
            """, caixaId);
        BigDecimal sangria = soma("select coalesce(sum(valor),0) from caixa_operacoes where caixa_id = ? and tipo = 'SANGRIA'", caixaId);
        BigDecimal suprimento = soma("select coalesce(sum(valor),0) from caixa_operacoes where caixa_id = ? and tipo = 'SUPRIMENTO'", caixaId);
        caixa.put("total_vendas", vendas);
        caixa.put("dinheiro_esperado", abertura.add(dinheiro).add(suprimento).subtract(sangria));
        caixa.put("sangria", sangria);
        caixa.put("suprimento", suprimento);
        return caixa;
    }

    private BigDecimal soma(String sql, Object... args) {
        Object result = jdbc.queryForObject(sql, Object.class, args);
        return result == null ? BigDecimal.ZERO : new BigDecimal(result.toString());
    }
}
