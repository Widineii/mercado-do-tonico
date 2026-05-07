package br.com.mercadotonico.service;

import br.com.mercadotonico.core.BusinessRules;
import br.com.mercadotonico.model.UsuarioLogado;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class VendaService {
    private final JdbcTemplate jdbc;

    public VendaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long finalizar(long caixaId, Long clienteId, String formaPagamento, BigDecimal desconto,
                          List<Map<String, String>> itens, List<Map<String, String>> pagamentos, UsuarioLogado usuario) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, String> item : itens) {
            BigDecimal qtd = n(item.get("quantidade"));
            BigDecimal preco = n(item.get("preco_unitario"));
            total = total.add(qtd.multiply(preco));
        }
        total = total.subtract(desconto == null ? BigDecimal.ZERO : desconto);
        KeyHolder key = new GeneratedKeyHolder();
        BigDecimal finalTotal = total;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                insert into vendas (caixa_id, operador_id, cliente_id, total, desconto, forma_pagamento, timestamp, status)
                values (?, ?, ?, ?, ?, ?, ?, 'CONCLUIDA')
                """, new String[]{"id"});
            ps.setLong(1, caixaId);
            ps.setLong(2, usuario.id());
            if (clienteId == null) ps.setObject(3, null); else ps.setLong(3, clienteId);
            ps.setBigDecimal(4, finalTotal);
            ps.setBigDecimal(5, desconto == null ? BigDecimal.ZERO : desconto);
            ps.setString(6, formaPagamento);
            ps.setString(7, LocalDateTime.now().toString());
            return ps;
        }, key);
        long vendaId = key.getKey().longValue();
        for (Map<String, String> item : itens) {
            long produtoId = Long.parseLong(item.get("produto_id"));
            BigDecimal qtd = n(item.get("quantidade"));
            Map<String, Object> produto = jdbc.queryForMap("select nome, preco_custo, estoque_atual from produtos where id = ?", produtoId);
            BusinessRules.ensureStockAvailable(n(produto.get("estoque_atual").toString()), qtd, produto.get("nome").toString());
            jdbc.update("""
                insert into venda_itens (venda_id, produto_id, quantidade, preco_unitario, custo_unitario)
                values (?, ?, ?, ?, ?)
                """, vendaId, produtoId, qtd, n(item.get("preco_unitario")), produto.get("preco_custo"));
            int updated = jdbc.update("update produtos set estoque_atual = estoque_atual - ? where id = ? and estoque_atual >= ?", qtd, produtoId, qtd);
            if (updated == 0) {
                BusinessRules.ensureStockAvailable(BigDecimal.ZERO, qtd, produto.get("nome").toString());
            }
            jdbc.update("""
                insert into movimentacao_estoque (produto_id, tipo, quantidade, referencia_id, operador_id, timestamp, observacao)
                values (?, 'VENDA', ?, ?, ?, ?, ?)
                """, produtoId, qtd, vendaId, usuario.id(), LocalDateTime.now().toString(), "Venda #" + vendaId);
        }
        for (Map<String, String> pagamento : pagamentos) {
            jdbc.update("insert into venda_pagamentos (venda_id, forma, valor) values (?, ?, ?)",
                    vendaId, pagamento.get("forma"), n(pagamento.get("valor")));
        }
        if ("FIADO".equals(formaPagamento) && clienteId != null) {
            jdbc.update("""
                insert into fiado (cliente_id, venda_id, valor, valor_pago, status, data_criacao)
                values (?, ?, ?, 0, 'ABERTO', ?)
                """, clienteId, vendaId, total, LocalDateTime.now().toString());
        }
        return vendaId;
    }

    @Transactional
    public void cancelarUltima(long caixaId, String motivo, UsuarioLogado usuario) {
        var rows = jdbc.queryForList("select id from vendas where caixa_id = ? and status = 'CONCLUIDA' order by id desc limit 1", caixaId);
        if (rows.isEmpty()) {
            return;
        }
        long vendaId = ((Number) rows.get(0).get("id")).longValue();
        for (Map<String, Object> item : jdbc.queryForList("select produto_id, quantidade from venda_itens where venda_id = ?", vendaId)) {
            jdbc.update("update produtos set estoque_atual = estoque_atual + ? where id = ?", item.get("quantidade"), item.get("produto_id"));
            jdbc.update("""
                insert into movimentacao_estoque (produto_id, tipo, quantidade, referencia_id, operador_id, timestamp, observacao)
                values (?, 'CANCELAMENTO', ?, ?, ?, ?, ?)
                """, item.get("produto_id"), item.get("quantidade"), vendaId, usuario.id(), LocalDateTime.now().toString(), motivo);
        }
        jdbc.update("update vendas set status = 'CANCELADA' where id = ?", vendaId);
        jdbc.update("update fiado set status = 'CANCELADO' where venda_id = ?", vendaId);
    }

    private BigDecimal n(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.replace(",", "."));
    }
}
