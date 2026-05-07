package br.com.mercadotonico.service;

import br.com.mercadotonico.model.UsuarioLogado;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class XmlNotaFiscalService {
    private final JdbcTemplate jdbc;

    public XmlNotaFiscalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, String>> preview(MultipartFile file) throws Exception {
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.getInputStream());
        doc.getDocumentElement().normalize();
        NodeList dets = doc.getElementsByTagName("det");
        List<Map<String, String>> itens = new ArrayList<>();
        for (int i = 0; i < dets.getLength(); i++) {
            Element det = (Element) dets.item(i);
            Element prod = (Element) det.getElementsByTagName("prod").item(0);
            itens.add(Map.of(
                    "codigo", text(prod, "cEAN"),
                    "nome", text(prod, "xProd"),
                    "quantidade", text(prod, "qCom"),
                    "custo", text(prod, "vUnCom")
            ));
        }
        return itens;
    }

    @Transactional
    public int importar(MultipartFile file, UsuarioLogado usuario) throws Exception {
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.getInputStream());
        doc.getDocumentElement().normalize();
        String cnpj = first(doc.getElementsByTagName("CNPJ"));
        String fornecedorNome = first(doc.getElementsByTagName("xNome"));
        jdbc.update("""
            insert into fornecedores (razao_social, nome_fantasia, cnpj)
            values (?, ?, ?)
            on conflict(cnpj) do update set razao_social=excluded.razao_social
            """, fornecedorNome.isBlank() ? "Fornecedor NF-e" : fornecedorNome, fornecedorNome, cnpj);
        Long fornecedorId = jdbc.queryForObject("select id from fornecedores where cnpj = ?", Long.class, cnpj);
        NodeList dets = doc.getElementsByTagName("det");
        for (int i = 0; i < dets.getLength(); i++) {
            Element prod = (Element) ((Element) dets.item(i)).getElementsByTagName("prod").item(0);
            String codigo = text(prod, "cEAN");
            String nome = text(prod, "xProd");
            BigDecimal qtd = money(text(prod, "qCom"));
            BigDecimal custo = money(text(prod, "vUnCom"));
            var existente = jdbc.queryForList("select id from produtos where codigo_barras = ?", codigo);
            if (existente.isEmpty()) {
                jdbc.update("""
                    insert into produtos (nome, codigo_barras, sku, categoria, unidade, preco_custo, preco_venda,
                    estoque_atual, estoque_minimo, fornecedor_id, ativo)
                    values (?, ?, ?, 'Mercearia', 'un', ?, ?, ?, 1, ?, 1)
                    """, nome, codigo, codigo, custo, custo.multiply(new BigDecimal("1.35")), qtd, fornecedorId);
                Long produtoId = jdbc.queryForObject("select id from produtos where codigo_barras = ?", Long.class, codigo);
                jdbc.update("""
                    insert into movimentacao_estoque (produto_id, tipo, quantidade, operador_id, timestamp, observacao)
                    values (?, 'ENTRADA', ?, ?, ?, ?)
                    """, produtoId, qtd, usuario.id(), LocalDateTime.now().toString(), "Entrada por XML NF-e");
            } else {
                long produtoId = ((Number) existente.get(0).get("id")).longValue();
                jdbc.update("update produtos set estoque_atual = estoque_atual + ?, preco_custo = ?, fornecedor_id = ? where id = ?",
                        qtd, custo, fornecedorId, produtoId);
                jdbc.update("""
                    insert into movimentacao_estoque (produto_id, tipo, quantidade, operador_id, timestamp, observacao)
                    values (?, 'ENTRADA', ?, ?, ?, ?)
                    """, produtoId, qtd, usuario.id(), LocalDateTime.now().toString(), "Entrada por XML NF-e");
            }
        }
        jdbc.update("""
            insert into notas_fiscais (fornecedor_id, numero_nf, data, xml_path, total, importado_em)
            values (?, ?, ?, ?, ?, ?)
            """, fornecedorId, first(doc.getElementsByTagName("nNF")), first(doc.getElementsByTagName("dhEmi")),
                file.getOriginalFilename(), money(first(doc.getElementsByTagName("vNF"))), LocalDateTime.now().toString());
        return dets.getLength();
    }

    private String text(Element element, String tag) {
        NodeList list = element.getElementsByTagName(tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().trim();
    }

    private String first(NodeList list) {
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().trim();
    }

    private BigDecimal money(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.replace(",", "."));
    }
}
