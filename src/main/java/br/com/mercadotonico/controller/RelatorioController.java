package br.com.mercadotonico.controller;

import br.com.mercadotonico.service.ProdutoService;
import br.com.mercadotonico.service.RelatorioService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/relatorios")
public class RelatorioController {
    private final RelatorioService relatorios;
    private final ProdutoService produtos;

    public RelatorioController(RelatorioService relatorios, ProdutoService produtos) {
        this.relatorios = relatorios;
        this.produtos = produtos;
    }

    @GetMapping
    String tela(@RequestParam(required = false) String inicio, @RequestParam(required = false) String fim, Model model, HttpSession session) {
        String i = inicio == null ? LocalDate.now().toString() : inicio;
        String f = fim == null ? LocalDate.now().toString() : fim;
        model.addAttribute("usuario", session.getAttribute("usuario"));
        model.addAttribute("flash", session.getAttribute("flash"));
        session.removeAttribute("flash");
        model.addAttribute("inicio", i);
        model.addAttribute("fim", f);
        model.addAttribute("vendas", relatorios.vendasPorPeriodo(i, f));
        model.addAttribute("maisVendidos", relatorios.maisVendidos());
        model.addAttribute("lucro", relatorios.lucroPorPeriodo(i, f));
        model.addAttribute("baixoEstoque", produtos.alertasEstoque());
        model.addAttribute("vencendo", produtos.alertasValidade());
        return "relatorios";
    }

    @GetMapping("/csv")
    ResponseEntity<String> csv(@RequestParam(defaultValue = "estoque") String tipo) {
        List<Map<String, Object>> rows = "mais-vendidos".equals(tipo) ? relatorios.maisVendidos() : relatorios.estoqueAtual();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=relatorio-" + tipo + ".csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(relatorios.csv(rows));
    }

    @GetMapping("/pdf")
    ResponseEntity<byte[]> pdf(@RequestParam(defaultValue = "estoque") String tipo) {
        List<Map<String, Object>> rows = "mais-vendidos".equals(tipo) ? relatorios.maisVendidos() : relatorios.estoqueAtual();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("relatorio-" + tipo + ".pdf").build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(relatorios.pdf("Relatorio " + tipo, rows));
    }
}
