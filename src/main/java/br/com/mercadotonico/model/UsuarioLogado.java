package br.com.mercadotonico.model;

public record UsuarioLogado(long id, String nome, String login, String role) {
    public boolean admin() {
        return "ADMIN".equals(role);
    }
}
