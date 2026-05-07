package br.com.mercadotonico.config;

import br.com.mercadotonico.core.UserPermissions;
import br.com.mercadotonico.model.UsuarioLogado;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")
                || path.equals("/login") || path.equals("/logout")) {
            return true;
        }
        HttpSession session = request.getSession();
        UsuarioLogado usuario = (UsuarioLogado) session.getAttribute("usuario");
        if (usuario == null) {
            response.sendRedirect("/login");
            return false;
        }
        return authorize(path, usuario, session, response);
    }

    private boolean authorize(String path, UsuarioLogado usuario, HttpSession session, HttpServletResponse response) throws Exception {
        if (path.equals("/") || path.equals("/logout")) {
            return true;
        }
        if (path.startsWith("/caixa")) {
            return allowedOrRedirect(UserPermissions.canAccessPdv(usuario.role()), session, response, "/caixa",
                    "Seu perfil nao possui acesso ao PDV.");
        }
        if (path.startsWith("/estoque") || path.startsWith("/fornecedores") || path.startsWith("/xml")) {
            return allowedOrRedirect(UserPermissions.canAccessInventory(usuario.role()), session, response, "/",
                    "Seu perfil nao possui acesso ao estoque administrativo.");
        }
        if (path.startsWith("/relatorios")) {
            return allowedOrRedirect(UserPermissions.canAccessReports(usuario.role()), session, response, "/",
                    "Seu perfil nao possui acesso aos relatorios gerenciais.");
        }
        if (path.startsWith("/fiado") || path.startsWith("/clientes")) {
            return allowedOrRedirect(UserPermissions.canAccessFiado(usuario.role()), session, response, "/",
                    "Seu perfil nao possui acesso ao modulo de fiado.");
        }
        return true;
    }

    private boolean allowedOrRedirect(boolean allowed, HttpSession session, HttpServletResponse response,
                                      String redirectTo, String message) throws Exception {
        if (allowed) {
            return true;
        }
        session.setAttribute("flash", message);
        response.sendRedirect(redirectTo);
        return false;
    }
}
