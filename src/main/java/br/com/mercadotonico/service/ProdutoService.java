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
public class ProdutoService {
    private final JdbcTemplate jdbc;

    public ProdutoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listar(String busca) {
        if (busca == null || busca.isBlank()) {
            return jdbc.queryForList("select * from produtos where ativo = 1 order by nome");
        }
        String q = "%" + busca.toLowerCase() + "%";
        return jdbc.queryForList("""
            select * from produtos
            where ativo = 1 and (lower(nome) like ? or lower(coalesce(codigo_barras,'')) like ? or lower(coalesce(sku,'')) like ?)
            order by nome
            """, q, q, q);
    }

    public List<Map<String, Object>> alertasEstoque() {
        return jdbc.queryForList("select * from produtos where ativo = 1 and estoque_atual <= estoque_minimo order by estoque_atual");
    }

    public List<Map<String, Object>> alertasValidade() {
        return jdbc.queryForList("""
            select * from produtos
            where ativo = 1 and validade is not null and date(validade) <= date('now','+30 day')
            order by validade
            """);
    }

    public Map<String, Object> buscar(long id) {
        return jdbc.queryForMap("select * from produtos where id = ?", id);
    }

    public Map<String, Object> buscarPorCodigo(String codigo) {
        var rows = jdbc.queryForList("""
            select * from produtos
            where ativo = 1 and (codigo_barras = ? or sku = ? or lower(nome) like lower(?))
            order by case when codigo_barras = ? or sku = ? then 0 else 1 end, nome
            limit 1
            """, codigo, codigo, "%" + codigo + "%", codigo, codigo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public void salvar(Map<String, String> f, UsuarioLogado usuario) {
        if (f.getOrDefault("id", "").isBlank()) {
            jdbc.update("""
                insert into produtos (nome, codigo_barras, sku, categoria, unidade, preco_custo, preco_venda, estoque_atual,
                estoque_minimo, localizacao, validade, fornecedor_id, ativo)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, nullif(?, ''), 1)
                """, f.get("nome"), f.get("codigo_barras"), f.get("sku"), f.get("categoria"), f.get("unidade"),
                    n(f.get("preco_custo")), n(f.get("preco_venda")), n(f.get("estoque_atual")), n(f.get("estoque_minimo")),
                    f.get("localizacao"), f.get("validade"), f.getOrDefault("fornecedor_id", ""));
        } else {
            jdbc.update("""
                update produtos set nome=?, codigo_barras=?, sku=?, categoria=?, unidade=?, preco_custo=?, preco_venda=?,
                estoque_atual=?, estoque_minimo=?, localizacao=?, validade=?, fornecedor_id=nullif(?, ''), ativo=?
                where id=?
                """, f.get("nome"), f.get("codigo_barras"), f.get("sku"), f.get("categoria"), f.get("unidade"),
                    n(f.get("preco_custo")), n(f.get("preco_venda")), n(f.get("estoque_atual")), n(f.get("estoque_minimo")),
                    f.get("localizacao"), f.get("validade"), f.getOrDefault("fornecedor_id", ""), bool(f.get("ativo")),
                    Long.parseLong(f.get("id")));
        }
        jdbc.update("insert into audit_log (usuario_id, acao, detalhe, timestamp) values (?, 'SALVAR_PRODUTO', ?, ?)",
                usuario.id(), f.get("nome"), LocalDateTime.now().toString());
    }

    @Transactional
    public void ajustarEstoque(long produtoId, BigDecimal quantidade, String tipo, String motivo, UsuarioLogado usuario) {
        BigDecimal delta = "SAIDA".equals(tipo) ? quantidade.negate() : quantidade;
        jdbc.update("update produtos set estoque_atual = estoque_atual + ? where id = ?", delta, produtoId);
        jdbc.update("""
            insert into movimentacao_estoque (produto_id, tipo, quantidade, operador_id, timestamp, observacao)
            values (?, ?, ?, ?, ?, ?)
            """, produtoId, tipo, quantidade, usuario.id(), LocalDateTime.now().toString(), motivo);
    }

    private BigDecimal n(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.replace(",", "."));
    }

    private int bool(String value) {
        return "on".equals(value) || "1".equals(value) || "true".equals(value) ? 1 : 0;
    }
}
