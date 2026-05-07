package br.com.mercadotonico.controller;

import br.com.mercadotonico.model.UsuarioLogado;
import br.com.mercadotonico.service.CadastroService;
import br.com.mercadotonico.service.FornecedorLookupService;
import br.com.mercadotonico.service.XmlNotaFiscalService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.Map;

@Controller
public class CadastroController {
    private final CadastroService cadastros;
    private final XmlNotaFiscalService xml;
    private final FornecedorLookupService fornecedorLookup;

    public CadastroController(CadastroService cadastros, XmlNotaFiscalService xml, FornecedorLookupService fornecedorLookup) {
        this.cadastros = cadastros;
        this.xml = xml;
        this.fornecedorLookup = fornecedorLookup;
    }

    @GetMapping("/fornecedores")
    String fornecedores(Model model, HttpSession session) {
        model.addAttribute("usuario", session.getAttribute("usuario"));
        model.addAttribute("flash", session.getAttribute("flash"));
        session.removeAttribute("flash");
        model.addAttribute("fornecedores", cadastros.fornecedores());
        return "fornecedores";
    }

    @PostMapping("/fornecedores")
    String salvarFornecedor(@RequestParam Map<String, String> form, HttpSession session) {
        cadastros.salvarFornecedor(form);
        session.setAttribute("flash", "Fornecedor salvo com sucesso.");
        return "redirect:/fornecedores";
    }

    @GetMapping("/fornecedores/cnpj")
    @ResponseBody
    ResponseEntity<Map<String, Object>> buscarFornecedorPorCnpj(@RequestParam String cnpj) {
        return fornecedorLookup.buscarPorCnpj(cnpj)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/fiado")
    String fiado(Model model, HttpSession session) {
        model.addAttribute("usuario", session.getAttribute("usuario"));
        model.addAttribute("flash", session.getAttribute("flash"));
        session.removeAttribute("flash");
        model.addAttribute("clientes", cadastros.clientes());
        model.addAttribute("fiados", cadastros.fiadosAbertos());
        return "fiado";
    }

    @PostMapping("/clientes")
    String salvarCliente(@RequestParam Map<String, String> form) {
        cadastros.salvarCliente(form);
        return "redirect:/fiado";
    }

    @PostMapping("/fiado/pagar")
    String pagarFiado(@RequestParam long fiadoId, @RequestParam BigDecimal valor, HttpSession session) {
        UsuarioLogado usuario = (UsuarioLogado) session.getAttribute("usuario");
        cadastros.pagarFiado(fiadoId, valor, usuario.id());
        return "redirect:/fiado";
    }

    @GetMapping("/xml")
    String xml(Model model, HttpSession session) {
        model.addAttribute("usuario", session.getAttribute("usuario"));
        model.addAttribute("flash", session.getAttribute("flash"));
        session.removeAttribute("flash");
        return "xml";
    }

    @PostMapping("/xml/preview")
    String preview(@RequestParam MultipartFile arquivo, Model model, HttpSession session) throws Exception {
        model.addAttribute("usuario", session.getAttribute("usuario"));
        model.addAttribute("itens", xml.preview(arquivo));
        return "xml";
    }

    @PostMapping("/xml/importar")
    String importar(@RequestParam MultipartFile arquivo, HttpSession session) throws Exception {
        int total = xml.importar(arquivo, (UsuarioLogado) session.getAttribute("usuario"));
        session.setAttribute("flash", total + " itens importados da NF-e.");
        return "redirect:/";
    }
}
