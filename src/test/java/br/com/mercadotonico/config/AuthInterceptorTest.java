package br.com.mercadotonico.config;

import br.com.mercadotonico.model.UsuarioLogado;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthInterceptorTest {
    private final AuthInterceptor interceptor = new AuthInterceptor();

    @Test
    void shouldRedirectCaixaAwayFromInventoryRoutes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/estoque");
        request.getSession().setAttribute("usuario", new UsuarioLogado(1L, "Caixa", "caixa1", "CAIXA"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertTrue("/".equals(response.getRedirectedUrl()));
        assertTrue("Seu perfil nao possui acesso ao estoque administrativo."
                .equals(request.getSession().getAttribute("flash")));
    }

    @Test
    void shouldRedirectEstoqueAwayFromPdvRoutes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/caixa/1/pos");
        request.getSession().setAttribute("usuario", new UsuarioLogado(2L, "Estoque", "estoque1", "ESTOQUE"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertTrue("/caixa".equals(response.getRedirectedUrl()));
        assertTrue("Seu perfil nao possui acesso ao PDV."
                .equals(request.getSession().getAttribute("flash")));
    }

    @Test
    void shouldAllowGerenteOnReports() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/relatorios");
        request.getSession().setAttribute("usuario", new UsuarioLogado(3L, "Gerente", "gerente", "GERENTE"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertTrue(response.getRedirectedUrl() == null);
    }
}
