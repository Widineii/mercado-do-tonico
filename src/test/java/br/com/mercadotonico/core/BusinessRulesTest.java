package br.com.mercadotonico.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessRulesTest {
    @Test
    void shouldRejectDiscountAboveProfileLimit() {
        assertThrows(AppException.class, () ->
                BusinessRules.validateDiscount(new BigDecimal("100"), new BigDecimal("16"), new BigDecimal("10")));
    }

    @Test
    void shouldAllowDiscountInsideProfileLimit() {
        assertDoesNotThrow(() ->
                BusinessRules.validateDiscount(new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("10")));
    }

    @Test
    void shouldRejectZeroPriceWhenProfileCannotAuthorize() {
        assertThrows(AppException.class, () ->
                BusinessRules.validateSalePrice(BigDecimal.ZERO, false));
    }

    @Test
    void shouldRejectStockConsumptionAboveAvailable() {
        assertThrows(AppException.class, () ->
                BusinessRules.ensureStockAvailable(new BigDecimal("2"), new BigDecimal("3"), "Arroz"));
    }
}
