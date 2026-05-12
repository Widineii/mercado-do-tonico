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
                        BarcodeLookupResult fromCache = c.payload;
                        BarcodeLookupResult merged = enrichWithNonOffProviders(barcode, fromCache, warnings);
                        return new Outcome(Optional.of(merged.toBuilder()
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
                    BarcodeLookupResult res = chainMergeAfterProvider(barcode, r.get(), provider.name(), warnings);
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

    /**
     * Resolve produto local pelo GTIN: {@code codigo_barras}, {@code sku} ou {@code codigo_interno}.
     */
    public Optional<Long> findRegisteredProductId(String rawBarcode) {
        String barcode = sanitize(rawBarcode);
        if (barcode.isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> row = findExistingProduct(barcode);
            if (row == null) {
                return Optional.empty();
            }
            return Optional.of(((Number) row.get("id")).longValue());
        } catch (Exception e) {
            SupportLogger.log("WARN", "barcode", "findRegisteredProductId", e.getMessage());
            return Optional.empty();
        }
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
                    "from produtos where ativo = 1 and ( " +
                    "  codigo_barras = ? " +
                    "  or trim(coalesce(sku, '')) = ? " +
                    "  or trim(coalesce(codigo_interno, '')) = ? " +
                    ") order by case when codigo_barras = ? then 0 else 1 end limit 1")) {
                ps.setString(1, barcode);
                ps.setString(2, barcode);
                ps.setString(3, barcode);
                ps.setString(4, barcode);
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
        String ean = firstNonBlank(asString(row.get("codigo_barras")), asString(row.get("sku")));
        return BarcodeLookupResult.builder()
                .barcode(ean)
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

    private BarcodeLookupResult parseOffCache(String barcode, String json) {
        return OpenFoodFactsProvider.parseJsonPayload(barcode, json).orElse(null);
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
                .averagePrice(readAvgPriceNode(root.path("avg_price")))
                .source(BarcodeLookupResult.Source.COSMOS_BLUESOFT)
                .rawJson(json)
                .build();
    }

    private static BigDecimal readAvgPriceNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            if (node.isNumber()) {
                return new BigDecimal(node.asText());
            }
            String t = node.asText("").trim().replace(',', '.');
            if (t.isEmpty()) {
                return null;
            }
            return new BigDecimal(t);
        } catch (Exception e) {
            return null;
        }
    }

    /** Cosmos (e outros apos OFF) trazem NCM/CEST/preco; OFF traz nome/imagem/categoria alimentar. */
    private BarcodeLookupResult enrichWithNonOffProviders(String barcode, BarcodeLookupResult base,
                                                          List<String> warnings) {
        BarcodeLookupResult acc = base;
        for (BarcodeProvider p : providers) {
            if (!p.isAvailable() || OpenFoodFactsProvider.NAME.equals(p.name())) {
                continue;
            }
            if (!needsSecondaryMerge(acc)) {
                break;
            }
            try {
                Optional<BarcodeLookupResult> o = p.lookup(barcode);
                if (o.isPresent()) {
                    acc = mergeOverlay(acc, o.get());
                }
            } catch (BarcodeLookupException ex) {
                appendProviderWarning(warnings, ex);
            } catch (Exception ex) {
                warnings.add(p.name() + ": " + ex.getMessage());
            }
        }
        return acc;
    }

    private BarcodeLookupResult chainMergeAfterProvider(String barcode, BarcodeLookupResult first,
                                                        String firstProviderName, List<String> warnings) {
        BarcodeLookupResult acc = first;
        boolean pastFirst = false;
        for (BarcodeProvider p : providers) {
            if (!pastFirst) {
                if (p.name().equals(firstProviderName)) {
                    pastFirst = true;
                }
                continue;
            }
            if (!p.isAvailable()) {
                continue;
            }
            if (!needsSecondaryMerge(acc)) {
                break;
            }
            try {
                Optional<BarcodeLookupResult> o = p.lookup(barcode);
                if (o.isPresent()) {
                    acc = mergeOverlay(acc, o.get());
                }
            } catch (BarcodeLookupException ex) {
                appendProviderWarning(warnings, ex);
            } catch (Exception ex) {
                warnings.add(p.name() + ": " + ex.getMessage());
            }
        }
        return acc;
    }

    private static boolean needsSecondaryMerge(BarcodeLookupResult a) {
        if (a == null) {
            return false;
        }
        return (a.ncm() == null || a.ncm().isBlank())
                || (a.cest() == null || a.cest().isBlank())
                || a.averagePrice() == null
                || (a.category() == null || a.category().isBlank());
    }

    private static BarcodeLookupResult mergeOverlay(BarcodeLookupResult base, BarcodeLookupResult ext) {
        return base.toBuilder()
                .ncm(coalesceStr(base.ncm(), ext.ncm()))
                .cest(coalesceStr(base.cest(), ext.cest()))
                .averagePrice(base.averagePrice() != null ? base.averagePrice() : ext.averagePrice())
                .category(coalesceStr(base.category(), ext.category()))
                .manufacturer(coalesceStr(base.manufacturer(), ext.manufacturer()))
                .imageUrl(coalesceStr(base.imageUrl(), ext.imageUrl()))
                .brand(coalesceStr(base.brand(), ext.brand()))
                .name(preferRicherProductName(base.name(), ext.name()))
                .build();
    }

    /** Prefere descricao mais completa (ex.: Cosmos) quando o nome local e curto ou vazio. */
    private static String preferRicherProductName(String baseName, String extName) {
        if (extName == null || extName.isBlank()) {
            return baseName;
        }
        if (baseName == null || baseName.isBlank()) {
            return extName;
        }
        if (extName.length() > baseName.length() + 6) {
            return extName;
        }
        return baseName;
    }

    private static String coalesceStr(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return a;
    }

    private static void appendProviderWarning(List<String> warnings, BarcodeLookupException ex) {
        String msg = ex.getMessage();
        if (ex.isOffline()) {
            warnings.add("Sem internet (" + ex.provider() + ")");
        } else if (ex.isTimeout()) {
            warnings.add("Tempo esgotado (" + ex.provider() + ")");
        } else if (ex.isUnauthorized()) {
            warnings.add("Chave invalida (" + ex.provider() + ")");
        } else if (ex.isRateLimited()) {
            warnings.add("Limite excedido (" + ex.provider() + ")");
        } else {
            warnings.add(msg == null ? "Erro em " + ex.provider() : msg);
        }
    }

    private static String txt(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
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

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return a;
    }
}
