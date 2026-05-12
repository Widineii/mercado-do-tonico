package br.com.mercadotonico.core;

/**
 * Senha fixa para autorizar operacoes sensiveis no desktop (ex.: venda acima do estoque,
 * aumento de limite de convenio, alteracao de limite no cadastro de cliente).
 * <p><b>Seguranca:</b> o valor nao e exibido nas telas; altere apenas nesta constante
 * (e comunique o dono da loja) se quiser trocar a senha fixa.
 */
public final class FixedAdminAuthorizationPassword {
    private FixedAdminAuthorizationPassword() {
    }

    public static final String PLAINTEXT = "admin123";
}
