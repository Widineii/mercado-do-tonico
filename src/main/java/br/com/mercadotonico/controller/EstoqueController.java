package br.com.mercadotonico.controller;

import br.com.mercadotonico.model.UsuarioLogado;
import br.com.mercadotonico.service.CadastroService;
import br.com.mercadotonico.service.ProdutoService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@Controller
@RequestMapping("/estoque")
public class EstoqueController {
    private final ProdutoService produtos;
    private final CadastroService cadastros;

    public EstoqueController(ProdutoService produtos, CadastroService cadastros) {
        this.produtos = produtos;
        this.cadastros = cadastros;
    }

    @GetMapping
    String listar(@RequestParam(required = false) String busca, Model model, HttpSession session) {
        model.addAttribute("usuario", session.getAttribute("usuario"));
        model.addAttribute("flash", session.getAttribute("flash"));
        session.removeAttribute("flash");
        model.addAttribute("produtos", produtos.listar(busca));
        model.addAttribute("fornecedores", cadastros.fornecedores());
        model.addAttribute("busca", busca);
        return "estoque";
    }

    @PostMapping("/salvar")
    String salvar(@RequestParam Map<String, String> form, HttpSession session) {
        produtos.salvar(form, (UsuarioLogado) session.getAttribute("usuario"));
        return "redirect:/estoque";
    }

    @PostMapping("/ajustar")
    String ajustar(@RequestParam long produtoId, @RequestParam BigDecimal quantidade,
                   @RequestParam String tipo, @RequestParam String motivo, HttpSession session) {
        produtos.ajustarEstoque(produtoId, quantidade, tipo, motivo, (UsuarioLogado) session.getAttribute("usuario"));
        return "redirect:/estoque";
    }
}
