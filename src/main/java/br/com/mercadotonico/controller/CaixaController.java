package br.com.mercadotonico.controller;

import br.com.mercadotonico.core.AppException;
import br.com.mercadotonico.core.PaymentAllocationService;
import br.com.mercadotonico.model.UsuarioLogado;
import br.com.mercadotonico.service.AuthService;
import br.com.mercadotonico.service.CaixaService;
import br.com.mercadotonico.service.CadastroService;
import br.com.mercadotonico.service.ProdutoService;
import br.com.mercadotonico.service.VendaService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/caixa")
public class CaixaController {
    private final CaixaService caixas;
    private final ProdutoService produtos;
    private final VendaService vendas;
    private final AuthService auth;
    private final CadastroService cadastros;

    public CaixaController(CaixaService caixas, ProdutoService produtos, VendaService vendas, AuthService auth, CadastroService cadastros) {
        this.caixas = caixas;
        this.produtos = produtos;
        this.vendas = vendas;
        this.auth = auth;
        this.cadastros = cadastros;
    }

    @GetMapping
    String escolher(Model model, HttpSession session) {
        model.addAttribute("usuario", usuario(session));
        model.addAttribute("caixas", caixas.listar());
        model.addAttribute("flash", session.getAttribute("flash"));
        session.removeAttribute("flash");
        return "caixas";
    }

    @PostMapping("/abrir")
    String abrir(@RequestParam int numero, @RequestParam BigDecimal fundo, HttpSession session) {
        UsuarioLogado usuario = usuario(session);
        String msg = caixas.abrir(numero, fundo, usuario);
        session.setAttribute("flash", msg);
        var caixa = caixas.listar().stream().filter(c -> ((Number) c.get("numero")).intValue() == numero).findFirst().orElseThrow();
        return "redirect:/caixa/" + caixa.get("id") + "/pos";
    }

    @GetMapping("/{id}/pos")
    String pos(@PathVariable long id, Model model, HttpSession session) {
        model.addAttribute("usuario", usuario(session));
        model.addAttribute("caixa", caixas.resumo((int) id));
        model.addAttribute("cart", cart(session));
        model.addAttribute("cartTotal", totalCart(session));
        model.addAttribute("clientes", cadastros.clientes());
        model.addAttribute("flash", session.getAttribute("flash"));
        session.removeAttribute("flash");
        return "pos";
    }

    @PostMapping("/{id}/add")
    String add(@PathVariable long id, @RequestParam String codigo, @RequestParam(defaultValue = "1") BigDecimal quantidade, HttpSession session) {
        Map<String, Object> produto = produtos.buscarPorCodigo(codigo);
        if (produto == null) {
            session.setAttribute("flash", "Produto não encontrado.");
            return "redirect:/caixa/" + id + "/pos";
        }
        Map<String, String> item = new HashMap<>();
        item.put("produto_id", produto.get("id").toString());
        item.put("nome", produto.get("nome").toString());
        item.put("quantidade", quantidade.toString());
        item.put("preco_unitario", produto.get("preco_venda").toString());
        item.put("total", quantidade.multiply(new BigDecimal(produto.get("preco_venda").toString())).toString());
        cart(session).add(item);
        return "redirect:/caixa/" + id + "/pos";
    }

    @PostMapping("/{id}/limpar")
    String limpar(@PathVariable long id, HttpSession session) {
        session.removeAttribute("cart");
        return "redirect:/caixa/" + id + "/pos";
    }

    @PostMapping("/{id}/finalizar")
    String finalizar(@PathVariable long id, @RequestParam MultiValueMap<String, String> form, HttpSession session) {
        try {
            BigDecimal desconto = dinheiro(form.getFirst("desconto"));
            if (desconto.compareTo(BigDecimal.ZERO) > 0 && !auth.validarPinGerente(form.getFirst("pin"))) {
                session.setAttribute("flash", "Desconto exige PIN de gerente.");
                return "redirect:/caixa/" + id + "/pos";
            }
            BigDecimal totalLiquido = totalCart(session).subtract(desconto);
            Map<String, BigDecimal> valores = new LinkedHashMap<>();
            valores.put("DINHEIRO", dinheiro(form.getFirst("pagamento_dinheiro")));
            valores.put("DEBITO", dinheiro(form.getFirst("pagamento_debito")));
            valores.put("CREDITO", dinheiro(form.getFirst("pagamento_credito")));
            valores.put("PIX", dinheiro(form.getFirst("pagamento_pix")));
            valores.put("FIADO", dinheiro(form.getFirst("pagamento_fiado")));
            Map<String, BigDecimal> pagamentosNormalizados = PaymentAllocationService.validateAndNormalize(totalLiquido, valores);
            Long clienteId = parseClienteId(form.getFirst("cliente_id"));
            if (pagamentosNormalizados.containsKey("FIADO") && clienteId == null) {
                throw new AppException("Selecione um cliente para lancar valor em fiado.");
            }
            List<Map<String, String>> pagamentos = pagamentosNormalizados.entrySet().stream()
                    .map(e -> Map.of("forma", e.getKey(), "valor", e.getValue().toString()))
                    .toList();
            String forma = PaymentAllocationService.paymentLabel(pagamentosNormalizados);
            long vendaId = vendas.finalizar(id, clienteId, forma, desconto, cart(session), pagamentos, usuario(session));
            session.removeAttribute("cart");
            session.setAttribute("flash", "Venda #" + vendaId + " finalizada com " + forma + ".");
            return "redirect:/caixa/" + id + "/pos";
        } catch (AppException e) {
            session.setAttribute("flash", e.getMessage());
            return "redirect:/caixa/" + id + "/pos";
        }
    }

    @PostMapping("/{id}/operacao")
    String operacao(@PathVariable long id, @RequestParam String tipo, @RequestParam BigDecimal valor,
                    @RequestParam String motivo, @RequestParam(required = false) String pin, HttpSession session) {
        if ("SANGRIA".equals(tipo) && !auth.validarPinGerente(pin)) {
            session.setAttribute("flash", "Sangria exige PIN de gerente.");
            return "redirect:/caixa/" + id + "/pos";
        }
        caixas.operacao((int) id, tipo, valor, motivo, usuario(session));
        return "redirect:/caixa/" + id + "/pos";
    }

    @PostMapping("/{id}/cancelar")
    String cancelar(@PathVariable long id, @RequestParam String motivo, @RequestParam String pin, HttpSession session) {
        if (!auth.validarPinGerente(pin)) {
            session.setAttribute("flash", "Cancelamento exige PIN de gerente.");
            return "redirect:/caixa/" + id + "/pos";
        }
        vendas.cancelarUltima(id, motivo, usuario(session));
        session.setAttribute("flash", "Ultima venda cancelada.");
        return "redirect:/caixa/" + id + "/pos";
    }

    @PostMapping("/{id}/fechar")
    String fechar(@PathVariable long id, @RequestParam BigDecimal dinheiroContado, HttpSession session) {
        session.setAttribute("fechamento", caixas.fechar((int) id, dinheiroContado, usuario(session)));
        return "redirect:/caixa";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> cart(HttpSession session) {
        Object cart = session.getAttribute("cart");
        if (cart == null) {
            cart = new ArrayList<Map<String, String>>();
            session.setAttribute("cart", cart);
        }
        return (List<Map<String, String>>) cart;
    }

    private BigDecimal totalCart(HttpSession session) {
        return cart(session).stream()
                .map(i -> new BigDecimal(i.get("quantidade")).multiply(new BigDecimal(i.get("preco_unitario"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal dinheiro(String valor) {
        return valor == null || valor.isBlank() ? BigDecimal.ZERO : new BigDecimal(valor.replace(",", "."));
    }

    private Long parseClienteId(String valor) {
        return valor == null || valor.isBlank() ? null : Long.parseLong(valor);
    }

    private UsuarioLogado usuario(HttpSession session) {
        return (UsuarioLogado) session.getAttribute("usuario");
    }
}
