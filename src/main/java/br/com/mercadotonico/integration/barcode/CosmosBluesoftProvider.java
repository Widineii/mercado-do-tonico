package br.com.mercadotonico.integration.barcode;

import br.com.mercadotonico.core.SupportLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Provider Cosmos Bluesoft (https://cosmos.bluesoft.com.br).
 *
 * <p>Catalogo brasileiro com NCM/CEST e dados fiscais — perfeito para PDV.
 * Requer chave de API (header {@code X-Cosmos-Token}). A chave e lida do
 * construtor; quando vazia, {@link #isAvailable()} retorna {@code false}
 * e o {@link BarcodeLookupService} pula este provider.</p>
 *
 * <p>Endpoint:
 * <pre>https://api.cosmos.bluesoft.com.br/gtins/{gtin}.json</pre>
 *
 * Resposta tipica (sucesso, abreviada):
 * <pre>{@code
 * {
 *   "gtin": 7891000100103,
 *   "description": "LEITE CONDENSADO MOCA NESTLE 395G",
 *   "brand": { "name": "Nestle", "picture": "https://..." },
 *   "ncm": { "code": "0402.99.00", "description": "Leite/creme..." },
 *   "cest": { "code": "17.001.00" },
 *   "thumbnail": "https://...",
 *   "avg_price": 5.99,
 *   "max_price": 7.50,
 *   "min_price": 4.20,
 *   "gpc": { "description": "Leite condensado" }
 * }
 * }</pre>
 *
 * Codigos relevantes:
 * <ul>
 *   <li>{@code 200} encontrado</li>
 *   <li>{@code 401} chave invalida ({@link BarcodeLookupException#unauthorized}</li>
 *   <li>{@code 404} GTIN nao cadastrado ({@link Optional#empty()})</li>
 *   <li>{@code 429} excesso de chamadas</li>
 * </ul>
 */
public final class CosmosBluesoftProvider implements BarcodeProvider {

    public static final String NAME = "COSMOS_BLUESOFT";
    private static final String ENDPOINT = "https://api.cosmos.bluesoft.com.br/gtins/%s.json";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final String token;

    public CosmosBluesoftProvider(String token) {
        this(token, Duration.ofSeconds(8));
    }

    public CosmosBluesoftProvider(String token, Duration timeout) {
        this.token = token == null ? "" : token.trim();
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_2)
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override public String name() { return NAME; }

    @Override public boolean isAvailable() { return !token.isEmpty(); }

    @Override
    public Optional<BarcodeLookupResult> lookup(String barcode) throws BarcodeLookupException {
        if (!isAvailable()) {
            return Optional.empty();
        }
        if (barcode == null || barcode.isBlank()) {
            return Optional.empty();
        }
        String url = String.format(Locale.ROOT, ENDPOINT, barcode.trim());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "MercadoDoTunicoPDV/1.0")
                .header("X-Cosmos-Token", token)
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw BarcodeLookupException.timeout(NAME, e);
        } catch (ConnectException | UnknownHostException e) {
            throw BarcodeLookupException.offline(NAME, e);
        } catch (IOException e) {
            throw BarcodeLookupException.offline(NAME, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BarcodeLookupException("Consulta interrompida", NAME, e,
                    false, false, false, false, 0);
        }

        int status = resp.statusCode();
        if (status == 404) {
            return Optional.empty();
        }
        if (status == 401 || status == 403) {
            throw BarcodeLookupException.unauthorized(NAME);
        }
        if (status == 429) {
            throw BarcodeLookupException.rateLimited(NAME);
        }
        if (status >= 400) {
            throw BarcodeLookupException.httpError(NAME, status, abbreviate(resp.body()));
        }

        try {
            JsonNode root = mapper.readTree(resp.body());
            String description = text(root, "description");
            if (description == null || description.isBlank()) {
                SupportLogger.log("INFO", "barcode", "Cosmos: payload sem descricao", barcode);
                return Optional.empty();
            }
            String brand = text(root.path("brand"), "name");
            String ncm = text(root.path("ncm"), "code");
            String cest = text(root.path("cest"), "code");
            String thumb = text(root, "thumbnail");
            BigDecimal avg = parsePrice(root.path("avg_price"));
            String gpcCategory = text(root.path("gpc"), "description");

            return Optional.of(BarcodeLookupResult.builder()
                    .barcode(barcode.trim())
                    .name(description)
                    .brand(brand)
                    .ncm(ncm)
                    .cest(cest)
                    .imageUrl(thumb)
                    .averagePrice(avg)
                    .category(gpcCategory)
                    .source(BarcodeLookupResult.Source.COSMOS_BLUESOFT)
                    .rawJson(resp.body())
                    .build());
        } catch (Exception e) {
            throw new BarcodeLookupException("Resposta invalida de " + NAME,
                    NAME, e, false, false, false, false, status);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal parsePrice(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return new BigDecimal(node.asText());
        String txt = node.asText("").trim();
        if (txt.isEmpty()) return null;
        try {
            return new BigDecimal(txt.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return null;
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
