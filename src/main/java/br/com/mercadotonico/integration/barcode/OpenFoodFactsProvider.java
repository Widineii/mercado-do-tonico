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
 * Provider gratuito da OpenFoodFacts (https://world.openfoodfacts.org).
 *
 * <p>Banco colaborativo (CC-BY-SA 3.0); cobertura excelente para alimentos
 * e bebidas com codigo de barras EAN-13. NAO requer chave de API.</p>
 *
 * <p>Endpoint v2:
 * <pre>https://world.openfoodfacts.org/api/v2/product/{barcode}.json</pre>
 *
 * Resposta tipica (sucesso, abreviada):
 * <pre>{@code
 * {
 *   "code": "7891000100103",
 *   "status": 1,
 *   "product": {
 *     "product_name_pt": "Leite Condensado Moca",
 *     "product_name": "Sweetened Condensed Milk",
 *     "brands": "Nestle, Moca",
 *     "categories": "Laticinios, Leites",
 *     "quantity": "395 g",
 *     "image_url": "https://images.openfoodfacts.org/.../front_pt.jpg",
 *     "image_front_url": "https://...",
 *     "manufacturing_places": "Brasil"
 *   }
 * }
 * }</pre>
 *
 * Quando o produto nao existe a API responde {@code "status": 0} com
 * {@code "status_verbose": "product not found"}; tratamos como
 * {@link Optional#empty()}.</p>
 */
public final class OpenFoodFactsProvider implements BarcodeProvider {

    public static final String NAME = "OPEN_FOOD_FACTS";
    private static final String ENDPOINT =
            "https://world.openfoodfacts.org/api/v2/product/%s.json"
                    + "?fields=code,product_name,product_name_pt,brands,categories,"
                    + "quantity,image_url,image_front_url,manufacturing_places";
    /** User-Agent recomendado pela OFF para identificar o cliente. */
    private static final String USER_AGENT = "MercadoDoTonicoPDV/1.0 (desktop)";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration timeout;

    public OpenFoodFactsProvider() {
        this(Duration.ofSeconds(8));
    }

    public OpenFoodFactsProvider(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_2)
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override public String name() { return NAME; }

    @Override public boolean isAvailable() { return true; /* sem chave */ }

    @Override
    public Optional<BarcodeLookupResult> lookup(String barcode) throws BarcodeLookupException {
        if (barcode == null || barcode.isBlank()) return Optional.empty();
        String url = String.format(Locale.ROOT, ENDPOINT, barcode.trim());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
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
        if (status == 429) {
            throw BarcodeLookupException.rateLimited(NAME);
        }
        if (status >= 400) {
            throw BarcodeLookupException.httpError(NAME, status, abbreviate(resp.body()));
        }

        try {
            JsonNode root = mapper.readTree(resp.body());
            int statusFlag = root.path("status").asInt(0);
            if (statusFlag != 1 || !root.has("product")) {
                SupportLogger.log("INFO", "barcode", "OpenFoodFacts: nao encontrado", barcode);
                return Optional.empty();
            }
            JsonNode product = root.get("product");
            String name = firstNonBlank(
                    text(product, "product_name_pt"),
                    text(product, "product_name"));
            String brands = text(product, "brands");
            String categories = text(product, "categories");
            String quantity = text(product, "quantity");
            String imageUrl = firstNonBlank(
                    text(product, "image_front_url"),
                    text(product, "image_url"));
            String manufacturer = text(product, "manufacturing_places");

            return Optional.of(BarcodeLookupResult.builder()
                    .barcode(barcode.trim())
                    .name(combineNameAndQuantity(name, quantity))
                    .brand(firstBrand(brands))
                    .manufacturer(manufacturer)
                    .category(firstCategory(categories))
                    .unit(unitFromQuantity(quantity))
                    .imageUrl(imageUrl)
                    .source(BarcodeLookupResult.Source.OPEN_FOOD_FACTS)
                    .rawJson(resp.body())
                    .build());
        } catch (Exception e) {
            throw new BarcodeLookupException("Resposta invalida de " + NAME,
                    NAME, e, false, false, false, false, status);
        }
    }

    // ---------------------- helpers de parsing ----------------------

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    /** "Nestle, Moca" -> "Nestle". */
    private static String firstBrand(String brands) {
        if (brands == null) return null;
        int comma = brands.indexOf(',');
        return (comma < 0 ? brands : brands.substring(0, comma)).trim();
    }

    /**
     * Categorias na OFF vem em hierarquia separada por virgula.
     * Mantemos a primeira (mais especifica costuma ser a ultima, mas o varejo
     * brasileiro raramente acerta o mapeamento; a primeira e mais util como dica).
     */
    private static String firstCategory(String categories) {
        if (categories == null) return null;
        int comma = categories.indexOf(',');
        String first = (comma < 0 ? categories : categories.substring(0, comma)).trim();
        // remove prefixos de idioma do tipo "en:milk" -> "milk"
        int colon = first.indexOf(':');
        return colon < 0 ? first : first.substring(colon + 1);
    }

    /** Anexa quantidade ao nome quando disponivel: "Leite Moca" + "395 g" -> "Leite Moca 395 g". */
    private static String combineNameAndQuantity(String name, String quantity) {
        if (name == null) return null;
        if (quantity == null || quantity.isBlank()) return name;
        if (name.toLowerCase(Locale.ROOT).contains(quantity.toLowerCase(Locale.ROOT))) {
            return name;
        }
        return name + " " + quantity;
    }

    /** "395 g" -> "g"; "1 L" -> "L"; "5 kg" -> "kg". Sem quantidade -> "un". */
    private static String unitFromQuantity(String quantity) {
        if (quantity == null) return "un";
        String q = quantity.toLowerCase(Locale.ROOT).replaceAll("[0-9.,\\s]", "");
        if (q.isEmpty()) return "un";
        return switch (q) {
            case "kg" -> "kg";
            case "g"  -> "g";
            case "l"  -> "L";
            case "ml" -> "ml";
            default   -> "un";
        };
    }

    private static String abbreviate(String s) {
        if (s == null) return null;
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /** Exposto para teste: facilita parser a partir de payload sintetico. */
    BigDecimal parseAvgPrice(JsonNode product) {
        // OpenFoodFacts nao retorna preco; method existe por simetria.
        return null;
    }
}
