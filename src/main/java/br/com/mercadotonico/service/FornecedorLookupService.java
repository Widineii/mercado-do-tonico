package br.com.mercadotonico.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class FornecedorLookupService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final boolean receitaWsEnabled;
    private final int timeoutSeconds;

    public FornecedorLookupService(
            JdbcTemplate jdbc,
            @Value("${app.receitaws.enabled:false}") boolean receitaWsEnabled,
            @Value("${app.receitaws.timeout-seconds:5}") int timeoutSeconds
    ) {
        this.jdbc = jdbc;
        this.receitaWsEnabled = receitaWsEnabled;
        this.timeoutSeconds = timeoutSeconds;
    }

    public Optional<Map<String, Object>> buscarPorCnpj(String cnpj) {
        String normalized = normalizeCnpj(cnpj);
        if (normalized == null) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> local = buscarLocal(normalized);
        return local.isPresent() ? local : buscarReceitaWs(normalized);
    }

    Optional<Map<String, Object>> buscarLocal(String cnpj) {
        var rows = jdbc.queryForList("""
                select razao_social, nome_fantasia, cnpj, telefone, email, endereco, contato
                from fornecedores
                where replace(replace(replace(cnpj, '.', ''), '/', ''), '-', '') = ?
                """, cnpj);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    Optional<Map<String, Object>> buscarReceitaWs(String cnpj) {
        if (!receitaWsEnabled) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://receitaws.com.br/v1/cnpj/" + URLEncoder.encode(cnpj, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return Optional.empty();
            }
            JsonNode json = objectMapper.readTree(response.body());
            if (json.hasNonNull("status") && "ERROR".equalsIgnoreCase(json.get("status").asText())) {
                return Optional.empty();
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("razao_social", text(json, "nome"));
            data.put("nome_fantasia", text(json, "fantasia"));
            data.put("cnpj", text(json, "cnpj"));
            data.put("telefone", text(json, "telefone"));
            data.put("email", text(json, "email"));
            data.put("endereco", endereco(json));
            data.put("contato", text(json, "nome"));
            return Optional.of(data);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    static String normalizeCnpj(String cnpj) {
        if (cnpj == null) {
            return null;
        }
        String digits = cnpj.replaceAll("\\D", "");
        return digits.length() == 14 ? digits : null;
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static String endereco(JsonNode json) {
        String logradouro = text(json, "logradouro");
        String numero = text(json, "numero");
        String bairro = text(json, "bairro");
        String municipio = text(json, "municipio");
        String uf = text(json, "uf");
        return String.join(", ",
                logradouro + (numero.isBlank() ? "" : ", " + numero),
                bairro,
                municipio + (uf.isBlank() ? "" : " - " + uf))
                .replaceAll("(^,\\s*|,\\s*,|,\\s*$)", "")
                .trim();
    }
}
