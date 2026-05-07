package br.com.mercadotonico.core;

import java.math.BigDecimal;

public final class BusinessRules {
    private BusinessRules() {
    }

    public static void requirePositive(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(fieldName + " deve ser maior que zero.");
        }
    }

    public static void requireNonNegative(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new AppException(fieldName + " nao pode ser negativo.");
        }
    }

    public static void requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new AppException(fieldName + " e obrigatorio.");
        }
    }

    public static void validateDiscount(BigDecimal subtotal, BigDecimal desconto, BigDecimal descontoMaximoPercentual) {
        requireNonNegative(subtotal, "Subtotal");
        requireNonNegative(desconto, "Desconto");
        if (desconto.compareTo(subtotal) > 0) {
            throw new AppException("Desconto nao pode ser maior que o subtotal.");
        }
        if (subtotal.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        BigDecimal percentual = desconto.multiply(BigDecimal.valueOf(100)).divide(subtotal, 2, java.math.RoundingMode.HALF_UP);
        if (percentual.compareTo(descontoMaximoPercentual) > 0) {
            throw new AppException("Desconto acima do limite do perfil.");
        }
    }

    public static void validateSalePrice(BigDecimal preco, boolean autorizaPrecoZero) {
        requireNonNegative(preco, "Preco");
        if (preco.compareTo(BigDecimal.ZERO) == 0 && !autorizaPrecoZero) {
            throw new AppException("Venda com preco zero nao e permitida para este perfil.");
        }
    }

    public static void ensureStockAvailable(BigDecimal estoqueAtual, BigDecimal quantidadeSolicitada, String produto) {
        requirePositive(quantidadeSolicitada, "Quantidade");
        if (estoqueAtual.compareTo(quantidadeSolicitada) < 0) {
            throw new AppException("Estoque insuficiente para " + produto + ".");
        }
    }
}
