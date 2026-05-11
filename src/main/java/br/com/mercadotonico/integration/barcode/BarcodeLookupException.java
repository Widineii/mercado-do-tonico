package br.com.mercadotonico.integration.barcode;

/**
 * Erro recuperavel durante consulta de catalogo externo.
 *
 * <p>Carrega flags semanticas para a UI mostrar a mensagem certa:
 * <ul>
 *   <li>{@link #offline} quando nao houve conectividade</li>
 *   <li>{@link #timeout} quando a API nao respondeu no tempo esperado</li>
 *   <li>{@link #rateLimited} quando o provider negou por excesso de chamadas</li>
 *   <li>{@link #unauthorized} quando a chave de API esta invalida ou ausente</li>
 * </ul>
 *
 * "Produto nao encontrado" NAO e excecao: providers retornam Optional vazio.
 */
public class BarcodeLookupException extends Exception {

    private final boolean offline;
    private final boolean timeout;
    private final boolean rateLimited;
    private final boolean unauthorized;
    private final int httpStatus;
    private final String provider;

    public BarcodeLookupException(String message, String provider, Throwable cause,
                                  boolean offline, boolean timeout,
                                  boolean rateLimited, boolean unauthorized,
                                  int httpStatus) {
        super(message, cause);
        this.provider = provider;
        this.offline = offline;
        this.timeout = timeout;
        this.rateLimited = rateLimited;
        this.unauthorized = unauthorized;
        this.httpStatus = httpStatus;
    }

    public static BarcodeLookupException offline(String provider, Throwable cause) {
        return new BarcodeLookupException("Sem conexao com a internet (" + provider + ")",
                provider, cause, true, false, false, false, 0);
    }

    public static BarcodeLookupException timeout(String provider, Throwable cause) {
        return new BarcodeLookupException("A consulta a " + provider + " expirou",
                provider, cause, false, true, false, false, 0);
    }

    public static BarcodeLookupException rateLimited(String provider) {
        return new BarcodeLookupException("Limite de consultas atingido em " + provider,
                provider, null, false, false, true, false, 429);
    }

    public static BarcodeLookupException unauthorized(String provider) {
        return new BarcodeLookupException("Chave de API invalida ou ausente em " + provider,
                provider, null, false, false, false, true, 401);
    }

    public static BarcodeLookupException httpError(String provider, int status, String body) {
        return new BarcodeLookupException(
                "Falha HTTP " + status + " em " + provider + (body == null ? "" : " :: " + body),
                provider, null, false, false, false, false, status);
    }

    public boolean isOffline()      { return offline; }
    public boolean isTimeout()      { return timeout; }
    public boolean isRateLimited()  { return rateLimited; }
    public boolean isUnauthorized() { return unauthorized; }
    public int httpStatus()         { return httpStatus; }
    public String provider()        { return provider; }
}
