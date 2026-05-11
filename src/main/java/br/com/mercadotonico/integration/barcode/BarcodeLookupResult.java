package br.com.mercadotonico.integration.barcode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Resultado normalizado da consulta de um EAN/GTIN em uma fonte externa.
 *
 * <p>Cada {@link BarcodeProvider} mapeia o JSON proprietario para esta
 * estrutura, deixando a {@link BarcodeLookupService} e a UI livres da
 * idiossincrasia de cada API. Campos sao todos opcionais: providers
 * preenchem o que conseguem e o restante fica {@code null}.</p>
 *
 * <p>Imutavel e thread-safe; construa via {@link Builder}.</p>
 */
public final class BarcodeLookupResult {

    /** Fontes conhecidas; o nome bate com o que e gravado em produto_lookup_cache.source. */
    public enum Source {
        DATABASE("DATABASE"),
        CACHE("CACHE"),
        OPEN_FOOD_FACTS("OPEN_FOOD_FACTS"),
        COSMOS_BLUESOFT("COSMOS_BLUESOFT"),
        MANUAL("MANUAL");

        private final String dbValue;

        Source(String dbValue) { this.dbValue = dbValue; }

        public String dbValue() { return dbValue; }

        public static Source fromDbValue(String s) {
            if (s == null) return MANUAL;
            for (Source src : values()) {
                if (src.dbValue.equalsIgnoreCase(s)) return src;
            }
            return MANUAL;
        }
    }

    private final String barcode;
    private final String name;
    private final String brand;
    private final String manufacturer;
    private final String category;
    private final String unit;
    private final String ncm;
    private final String cest;
    private final BigDecimal averagePrice;
    private final String imageUrl;
    private final Source source;
    private final String rawJson;

    private BarcodeLookupResult(Builder b) {
        this.barcode = Objects.requireNonNull(b.barcode, "barcode");
        this.name = b.name;
        this.brand = b.brand;
        this.manufacturer = b.manufacturer;
        this.category = b.category;
        this.unit = b.unit == null || b.unit.isBlank() ? "un" : b.unit;
        this.ncm = b.ncm;
        this.cest = b.cest;
        this.averagePrice = b.averagePrice;
        this.imageUrl = b.imageUrl;
        this.source = b.source == null ? Source.MANUAL : b.source;
        this.rawJson = b.rawJson;
    }

    public String barcode() { return barcode; }
    public String name() { return name; }
    public String brand() { return brand; }
    public String manufacturer() { return manufacturer; }
    public String category() { return category; }
    public String unit() { return unit; }
    public String ncm() { return ncm; }
    public String cest() { return cest; }
    public BigDecimal averagePrice() { return averagePrice; }
    public String imageUrl() { return imageUrl; }
    public Source source() { return source; }
    public String rawJson() { return rawJson; }

    /** {@code true} se conseguimos pelo menos um nome de produto. */
    public boolean hasUsefulData() {
        return name != null && !name.isBlank();
    }

    /** Reaproveita os campos atuais para criar um novo objeto trocando algum atributo. */
    public Builder toBuilder() {
        return new Builder()
                .barcode(barcode)
                .name(name)
                .brand(brand)
                .manufacturer(manufacturer)
                .category(category)
                .unit(unit)
                .ncm(ncm)
                .cest(cest)
                .averagePrice(averagePrice)
                .imageUrl(imageUrl)
                .source(source)
                .rawJson(rawJson);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String barcode;
        private String name;
        private String brand;
        private String manufacturer;
        private String category;
        private String unit;
        private String ncm;
        private String cest;
        private BigDecimal averagePrice;
        private String imageUrl;
        private Source source;
        private String rawJson;

        public Builder barcode(String v)        { this.barcode = v; return this; }
        public Builder name(String v)           { this.name = v; return this; }
        public Builder brand(String v)          { this.brand = v; return this; }
        public Builder manufacturer(String v)   { this.manufacturer = v; return this; }
        public Builder category(String v)       { this.category = v; return this; }
        public Builder unit(String v)           { this.unit = v; return this; }
        public Builder ncm(String v)            { this.ncm = v; return this; }
        public Builder cest(String v)           { this.cest = v; return this; }
        public Builder averagePrice(BigDecimal v){ this.averagePrice = v; return this; }
        public Builder imageUrl(String v)       { this.imageUrl = v; return this; }
        public Builder source(Source v)         { this.source = v; return this; }
        public Builder rawJson(String v)        { this.rawJson = v; return this; }

        public BarcodeLookupResult build() { return new BarcodeLookupResult(this); }
    }

    @Override
    public String toString() {
        return "BarcodeLookupResult{barcode=" + barcode + ", name=" + name
                + ", brand=" + brand + ", source=" + source + "}";
    }
}
