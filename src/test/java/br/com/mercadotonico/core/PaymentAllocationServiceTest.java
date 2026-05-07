package br.com.mercadotonico.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentAllocationServiceTest {
    @Test
    void shouldAcceptExactPaymentAllocation() {
        Map<String, BigDecimal> result = PaymentAllocationService.validateAndNormalize(
                new BigDecimal("100"),
                Map.of("DINHEIRO", new BigDecimal("40"), "PIX", new BigDecimal("60"))
        );
        assertEquals(2, result.size());
        assertEquals("DINHEIRO+PIX", PaymentAllocationService.paymentLabel(result));
    }

    @Test
    void shouldRejectDifferentTotal() {
        assertThrows(AppException.class, () -> PaymentAllocationService.validateAndNormalize(
                new BigDecimal("100"),
                Map.of("DINHEIRO", new BigDecimal("40"), "PIX", new BigDecimal("50"))
        ));
    }

    @Test
    void shouldRejectNegativePaymentValues() {
        assertThrows(AppException.class, () -> PaymentAllocationService.validateAndNormalize(
                new BigDecimal("10"),
                Map.of("DINHEIRO", new BigDecimal("-1"), "PIX", new BigDecimal("11"))
        ));
    }
}
