package br.com.mercadotonico.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class FormatService {
    private static final Locale BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public String dinheiro(Object value) {
        BigDecimal number = value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
        return NumberFormat.getCurrencyInstance(BR).format(number);
    }

    public String dataHora(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        return LocalDateTime.parse(iso).format(DATE_TIME);
    }
}
