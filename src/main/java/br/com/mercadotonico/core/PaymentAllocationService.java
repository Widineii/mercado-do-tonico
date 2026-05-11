package br.com.mercadotonico.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class PaymentAllocationService {
    public static final List<String> SUPPORTED_METHODS = List.of("DINHEIRO", "DEBITO", "CREDITO", "PIX", "FIADO", "CREDITO_TROCA");

    private PaymentAllocationService() {
    }

    public static Map<String, BigDecimal> validateAndNormalize(BigDecimal totalVenda, Map<String, BigDecimal> valores) {
        BusinessRules.requirePositive(totalVenda, "Total da venda");
        if (valores == null || valores.isEmpty()) {
            throw new AppException("Informe ao menos uma forma de pagamento.");
        }

        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        for (String method : SUPPORTED_METHODS) {
            BigDecimal value = valores.getOrDefault(method, BigDecimal.ZERO);
            BusinessRules.requireNonNegative(value, "Valor de " + paymentDisplayName(method));
            BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
            if (scaled.compareTo(BigDecimal.ZERO) > 0) {
                normalized.put(method, scaled);
            }
        }

        if (normalized.isEmpty()) {
            throw new AppException("Informe ao menos uma forma de pagamento com valor maior que zero.");
        }

        BigDecimal totalPago = normalized.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalEsperado = totalVenda.setScale(2, RoundingMode.HALF_UP);
        if (totalPago.compareTo(totalEsperado) != 0) {
            throw new AppException("A soma dos pagamentos deve ser exatamente " + totalEsperado + ".");
        }

        return normalized;
    }

    public static String paymentLabel(Map<String, BigDecimal> allocations) {
        return allocations.keySet().stream()
                .map(PaymentAllocationService::paymentDisplayName)
                .collect(Collectors.joining("+"));
    }

    /** Nome exibido para o operador (codigo interno de venda continua {@code FIADO}). */
    public static String paymentDisplayName(String method) {
        if ("FIADO".equals(method)) {
            return "Convênio";
        }
        return method;
    }
}
