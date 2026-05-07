package br.com.mercadotonico.controller;

import br.com.mercadotonico.service.AuthService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    @PostMapping("/login")
    String entrar(@RequestParam String login, @RequestParam String senha, HttpSession session, Model model) {
        return auth.login(login, senha)
                .map(usuario -> {
                    session.setAttribute("usuario", usuario);
                    return "redirect:/";
                })
                .orElseGet(() -> {
                    model.addAttribute("erro", "Login ou senha inválidos.");
                    return "login";
                });
    }

    @GetMapping("/logout")
    String sair(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }
}
