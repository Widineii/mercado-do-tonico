package br.com.mercadotonico.integration.barcode;

import br.com.mercadotonico.core.SupportLogger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Orquestra a busca de um produto por GTIN/EAN seguindo a regra:
 *
 * <ol>
 *   <li>Tabela <b>{@code produtos}</b> local (ja cadastrado): retorno
 *       imediato com {@link Source#DATABASE} e o id existente.</li>
 *   <li>Tabela <b>{@code produto_lookup_cache}</b> (consultas anteriores):
 *       retorno em milissegundos sem custo de rede; respeita TTL
 *       configurado em {@link #cacheTtl()}.</li>
 *   <li>Cada {@link BarcodeProvider} configurado, na ordem de
 *       prioridade. Resultado e gravado no cache (positivo ou negativo).</li>
 * </ol>
 *
 * <p>Thread-safe (a lista de providers e {@link CopyOnWriteArrayList};
 * todos os providers sao stateless). Pode ser chamado de qualquer thread —
 * a UI Swing usa {@link javax.swing.SwingWorker} para nao bloquear a EDT.</p>
 *
 * <p>Logs vao para {@link SupportLogger} (canal {@code barcode}).</p>
 */
public final class BarcodeLookupService {

    /** Resultado da consulta orquestrada. Sempre presente; o {@code result} e opcional. */
    public static final class Outcome {
        public final Optional<BarcodeLookupResult> result;
        public final Optional<Long> existingProductId;
        public final BarcodeLookupResult.Source source;
        public final List<String> warnings;

        Outcome(Optional<BarcodeLookupResult> result,
                Optional<Long> existingProductId,
                BarcodeLookupResult.Source source,
                List<String> warnings) {
            this.result = result;
            this.existingProductId = existingProductId;
            this.source = source;
            this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** {@code true} quando o EAN ja esta cadastrado em {@code produtos}. */
        public boolean isAlreadyRegistered() { return existingProductId.isPresent(); }

        /** {@code true} quando algum provider/cache devolveu dados utilizaveis. */
        public boolean hasResult() { return result.isPresent(); }
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final Connection con;
    private final List<BarcodeProvider> providers = new CopyOnWriteArrayList<>();
    private Duration cacheTtl = Duration.ofDays(30);
    /** Cache "negativo" (404) e mais curto: produtos novos podem ser cadastrados. */
    private Duration negativeCacheTtl = Duration.ofDays(2);

    public BarcodeLookupService(Connection con) {
        this.con = Objects.requireNonNull(con, "connection");
    }

    public BarcodeLookupService addProvider(BarcodeProvider provider) {
        if (provider != null) providers.add(provider);
        return this;
    }

    public List<BarcodeProvider> providers() { return List.copyOf(providers); }

    public Duration cacheTtl() { return cacheTtl; }
    public void setCacheTtl(Duration d) { this.cacheTtl = d; }
    public void setNegativeCacheTtl(Duration d) { this.negativeCacheTtl = d; }

    // ---------------------------------------------------------------
    // API publica
    // ---------------------------------------------------------------

    /**
     * Consulta sincrona (chamar fora da EDT). Nunca lanca; warnings ficam
     * em {@link Outcome#warnings} para a UI exibir como toasts.
     */
    public Outcome lookup(String rawBarcode) {
        String barcode = sanitize(rawBarcode);
        List<String> warnings = new ArrayList<>();
        if (barcode.isEmpty()) {
            return new Outcome(Optional.empty(), Optional.empty(),
                    BarcodeLookupResult.Source.MANUAL, warnings);
        }

        // 1) tabela produtos
        try {
            Map<String, Object> row = findExistingProduct(barcode);
            if (row != null) {
                Long id = ((Number) row.get("id")).longValue();
                BarcodeLookupResult fromDb = mapProductRowToResult(row);
                SupportLogger.log("INFO", "barcode", "Encontrado em produtos", barcode);
                return new Outcome(Optional.of(fromDb), Optional.of(id),
                        BarcodeLookupResult.Source.DATABASE, warnings);
            }
        } catch (Exception e) {
            SupportLogger.log("WARN", "barcode", "Falha lendo produtos", e.getMessage());
        }

        // 2) cache de lookups
        try {
            Optional<CachedEntry> cached = readCache(barcode);
            if (cached.isPresent()) {
                CachedEntry c = cached.get();
                if (!c.isExpired(cacheTtl, negativeCacheTtl)) {
                    if (c.found && c.payload != null) {
                        SupportLogger.log("INFO", "barcode", "Encontrado em cache", barcode + " :: " + c.source);
                        return new Outcome(Optional.of(c.payload.toBuilder()
                                .source(BarcodeLookupResult.Source.CACHE)
                                .build()),
                                Optional.empty(),
                                BarcodeLookupResult.Source.CACHE, warnings);
                    } else {
                        SupportLogger.log("INFO", "barcode", "Cache negativo", barcode);
                        return new Outcome(Optional.empty(), Optional.empty(),
                                BarcodeLookupResult.Source.CACHE, warnings);
                    }
                }
            }
        } catch (Exception e) {
            SupportLogger.log("WARN", "barcode", "Falha lendo cache", e.getMessage());
        }

        // 3) providers externos
        for (BarcodeProvider provider : providers) {
            if (!provider.isAvailable()) {
                SupportLogger.log("INFO", "barcode", "Provider indisponivel (skip)", provider.name());
                continue;
            }
            try {
                Optional<BarcodeLookupResult> r = provider.lookup(barcode);
                if (r.isPresent()) {
                    BarcodeLookupResult res = r.get();
                    persistCache(barcode, res, true);
                    SupportLogger.log("INFO", "barcode", "Encontrado via " + provider.name(), barcode);
                    return new Outcome(Optional.of(res), Optional.empty(),
                            res.source(), warnings);
                }
            } catch (BarcodeLookupException e) {
                String msg = e.getMessage();
                if (e.isOffline())          warnings.add("Sem internet (" + e.provider() + ")");
                else if (e.isTimeout())     warnings.add("Tempo esgotado (" + e.provider() + ")");
                else if (e.isUnauthorized())warnings.add("Chave invalida (" + e.provider() + ")");
                else if (e.isRateLimited()) warnings.add("Limite excedido (" + e.provider() + ")");
                else                        warnings.add(msg == null ? "Erro em " + e.provider() : msg);
                SupportLogger.log("WARN", "barcode", "Provider falhou: " + provider.name(), msg);
                // continua tentando os outros providers
            } catch (Exception e) {
                warnings.add("Erro em " + provider.name() + ": " + e.getMessage());
                SupportLogger.log("ERROR", "barcode", "Provider error: " + provider.name(),
                        String.valueOf(e.getMessage()));
            }
        }

        // 4) ninguem encontrou: cacheia negativo (TTL curto) e devolve vazio
        try {
            persistCache(barcode, null, false);
        } catch (Exception e) {
            SupportLogger.log("WARN", "barcode", "Falha cacheando negativo", e.getMessage());
        }
        return new Outcome(Optional.empty(), Optional.empty(),
                BarcodeLookupResult.Source.MANUAL, warnings);
    }

    /** Limpa todo o cache (uso administrativo). */
    public int clearCache() throws Exception {
        synchronized (con) {
            try (PreparedStatement ps = con.prepareStatement("delete from produto_lookup_cache")) {
                return ps.executeUpdate();
            }
        }
    }

    // ---------------------------------------------------------------
    // Database access
    // ---------------------------------------------------------------

    private Map<String, Object> findExistingProduct(String barcode) throws Exception {
        synchronized (con) {
            try (PreparedStatement ps = con.prepareStatement(
                    "select id, codigo_barras, sku, nome, marca, fabricante, categoria, " +
                    "unidade, ncm, cest, preco_custo, preco_venda, estoque_atual, " +
                    "estoque_minimo, localizacao, validade, imagem_url, observacoes " +
                    "from produtos where codigo_barras = ? and ativo = 1 limit 1")) {
                ps.setString(1, barcode);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("codigo_barras", rs.getString("codigo_barras"));
                    m.put("sku", rs.getString("sku"));
                    m.put("nome", rs.getString("nome"));
                    m.put("marca", rs.getString("marca"));
                    m.put("fabricante", rs.getString("fabricante"));
                    m.put("categoria", rs.getString("categoria"));
                    m.put("unidade", rs.getString("unidade"));
                    m.put("ncm", rs.getString("ncm"));
                    m.put("cest", rs.getString("cest"));
                    m.put("preco_custo", rs.getBigDecimal("preco_custo"));
                    m.put("preco_venda", rs.getBigDecimal("preco_venda"));
                    m.put("estoque_atual", rs.getBigDecimal("estoque_atual"));
                    m.put("estoque_minimo", rs.getBigDecimal("estoque_minimo"));
                    m.put("localizacao", rs.getString("localizacao"));
                    m.put("validade", rs.getString("validade"));
                    m.put("imagem_url", rs.getString("imagem_url"));
                    return m;
                }
            }
        }
    }

    private BarcodeLookupResult mapProductRowToResult(Map<String, Object> row) {
        return BarcodeLookupResult.builder()
                .barcode(asString(row.get("codigo_barras")))
                .name(asString(row.get("nome")))
                .brand(asString(row.get("marca")))
                .manufacturer(asString(row.get("fabricante")))
                .category(asString(row.get("categoria")))
                .unit(asString(row.get("unidade")))
                .ncm(asString(row.get("ncm")))
                .cest(asString(row.get("cest")))
                .imageUrl(asString(row.get("imagem_url")))
                .averagePrice((BigDecimal) row.get("preco_venda"))
                .source(BarcodeLookupResult.Source.DATABASE)
                .build();
    }

    // ---------------------------------------------------------------
    // Cache (produto_lookup_cache)
    // ---------------------------------------------------------------

    private static final class CachedEntry {
        final boolean found;
        final BarcodeLookupResult.Source source;
        final Instant fetchedAt;
        final BarcodeLookupResult payload;

        CachedEntry(boolean found, BarcodeLookupResult.Source source,
                    Instant fetchedAt, BarcodeLookupResult payload) {
            this.found = found;
            this.source = source;
            this.fetchedAt = fetchedAt;
            this.payload = payload;
        }

        boolean isExpired(Duration positiveTtl, Duration negativeTtl) {
            Duration age = Duration.between(fetchedAt, Instant.now());
            Duration ttl = found ? positiveTtl : negativeTtl;
            return age.compareTo(ttl) > 0;
        }
    }

    private Optional<CachedEntry> readCache(String barcode) throws Exception {
        synchronized (con) {
            try (PreparedStatement ps = con.prepareStatement(
                    "select source, payload_json, fetched_at, found " +
                    "from produto_lookup_cache where barcode = ?")) {
                ps.setString(1, barcode);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    boolean found = rs.getInt("found") == 1;
                    String src = rs.getString("source");
                    String payloadJson = rs.getString("payload_json");
                    String fetched = rs.getString("fetched_at");
                    Instant when = parseInstant(fetched);
                    BarcodeLookupResult payload = null;
                    if (found && payloadJson != null && !payloadJson.isBlank()) {
                        // o cache guarda o JSON bruto + uma serializacao simples dos campos.
                        // Para nao fazer parsing pesado aqui, refazemos com Jackson.
                        payload = parseCachedPayload(barcode, src, payloadJson);
                    }
                    return Optional.of(new CachedEntry(
                            found,
                            BarcodeLookupResult.Source.fromDbValue(src),
                            when,
                            payload));
                }
            }
        }
    }

    private BarcodeLookupResult parseCachedPayload(String barcode, String source, String json) {
        // Reaproveita o parser do provider correspondente quando possivel; fallback minimo.
        try {
            if (BarcodeLookupResult.Source.OPEN_FOOD_FACTS.dbValue().equalsIgnoreCase(source)) {
                // OFF: usa um parser simples reproduzindo o que o provider faria.
                return parseOffCache(barcode, json);
            }
            if (BarcodeLookupResult.Source.COSMOS_BLUESOFT.dbValue().equalsIgnoreCase(source)) {
                return parseCosmosCache(barcode, json);
            }
        } catch (Exception ignored) {
            // Em caso de falha, retornamos nulo para forcar re-consulta.
        }
        return null;
    }

    private BarcodeLookupResult parseOffCache(String barcode, String json) throws Exception {
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        com.fasterxml.jackson.databind.JsonNode product = root.path("product");
        String name = txt(product.path("product_name_pt"));
        if (name == null) name = txt(product.path("product_name"));
        String quantity = txt(product.path("quantity"));
        if (name != null && quantity != null
                && !name.toLowerCase().contains(quantity.toLowerCase())) {
            name = name + " " + quantity;
        }
        String brand = txt(product.path("brands"));
        if (brand != null) {
            int comma = brand.indexOf(',');
            if (comma >= 0) brand = brand.substring(0, comma).trim();
        }
        String image = txt(product.path("image_front_url"));
        if (image == null) image = txt(product.path("image_url"));
        return BarcodeLookupResult.builder()
                .barcode(barcode)
                .name(name)
                .brand(brand)
                .imageUrl(image)
                .unit(unitFromQuantity(quantity))
                .source(BarcodeLookupResult.Source.OPEN_FOOD_FACTS)
                .rawJson(json)
                .build();
    }

    private BarcodeLookupResult parseCosmosCache(String barcode, String json) throws Exception {
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        return BarcodeLookupResult.builder()
                .barcode(barcode)
                .name(txt(root.path("description")))
                .brand(txt(root.path("brand").path("name")))
                .ncm(txt(root.path("ncm").path("code")))
                .cest(txt(root.path("cest").path("code")))
                .category(txt(root.path("gpc").path("description")))
                .imageUrl(txt(root.path("thumbnail")))
                .source(BarcodeLookupResult.Source.COSMOS_BLUESOFT)
                .rawJson(json)
                .build();
    }

    private static String unitFromQuantity(String q) {
        if (q == null) return "un";
        String s = q.toLowerCase().replaceAll("[0-9.,\\s]", "");
        return switch (s) {
            case "kg" -> "kg";
            case "g"  -> "g";
            case "l"  -> "L";
            case "ml" -> "ml";
            default   -> "un";
        };
    }

    private static String txt(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String s = node.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private void persistCache(String barcode, BarcodeLookupResult result, boolean found) throws Exception {
        String source = result == null
                ? "NOT_FOUND"
                : result.source().dbValue();
        String payload = result == null ? "{}" : (result.rawJson() == null ? "{}" : result.rawJson());
        String now = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
        synchronized (con) {
            try (PreparedStatement ps = con.prepareStatement(
                    "insert into produto_lookup_cache (barcode, source, payload_json, fetched_at, found) " +
                    "values (?, ?, ?, ?, ?) " +
                    "on conflict(barcode) do update set " +
                    "  source = excluded.source," +
                    "  payload_json = excluded.payload_json," +
                    "  fetched_at = excluded.fetched_at," +
                    "  found = excluded.found")) {
                ps.setString(1, barcode);
                ps.setString(2, source);
                ps.setString(3, payload);
                ps.setString(4, now);
                ps.setInt(5, found ? 1 : 0);
                ps.executeUpdate();
            }
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return Instant.EPOCH;
        try {
            // formato gravado: ISO_LOCAL_DATE_TIME + "Z"
            if (s.endsWith("Z")) {
                return Instant.parse(s);
            }
            return Instant.parse(s + "Z");
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("[^0-9A-Za-z]", "");
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
}
