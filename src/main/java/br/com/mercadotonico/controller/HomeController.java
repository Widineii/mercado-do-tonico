package br.com.mercadotonico.controller;

import br.com.mercadotonico.service.CaixaService;
import br.com.mercadotonico.service.CadastroService;
import br.com.mercadotonico.service.ProdutoService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    private final ProdutoService produtos;
    private final CaixaService caixas;
    private final CadastroService cadastros;

    public HomeController(ProdutoService produtos, CaixaService caixas, CadastroService cadastros) {
        this.produtos = produtos;
        this.caixas = caixas;
        this.cadastros = cadastros;
    }

    @GetMapping("/")
    String dashboard(Model model, HttpSession session) {
        model.addAttribute("usuario", session.getAttribute("usuario"));
        model.addAttribute("flash", session.getAttribute("flash"));
        session.removeAttribute("flash");
        model.addAttribute("caixas", caixas.listar());
        model.addAttribute("baixoEstoque", produtos.alertasEstoque());
        model.addAttribute("vencendo", produtos.alertasValidade());
        model.addAttribute("fiados", cadastros.fiadosAbertos());
        return "dashboard";
    }
}
