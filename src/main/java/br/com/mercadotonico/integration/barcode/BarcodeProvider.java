package br.com.mercadotonico.integration.barcode;

import java.util.Optional;

/**
 * Provider que consulta uma fonte externa de catalogo de produtos por GTIN/EAN.
 *
 * <p>Implementacoes ficam stateless e thread-safe. A {@link BarcodeLookupService}
 * itera por todos os providers disponiveis (na ordem de prioridade) ate algum
 * encontrar o produto. "Nao encontrado" e {@link Optional#empty()}; falhas
 * de rede/HTTP sao reportadas como {@link BarcodeLookupException}.</p>
 *
 * <p>Providers podem ser adicionados via {@link BarcodeLookupService#addProvider}
 * sem alterar o restante do sistema (Open/Closed Principle).</p>
 */
public interface BarcodeProvider {

    /** Nome curto / estavel; gravado no cache e em logs. */
    String name();

    /**
     * Indica se o provider tem configuracao suficiente (ex: chave de API).
     * Quando falso, a {@link BarcodeLookupService} pula este provider sem custo.
     */
    boolean isAvailable();

    /**
     * Consulta o EAN. Retorna {@link Optional#empty()} quando o produto nao
     * existe na fonte. Lanca {@link BarcodeLookupException} para erros de
     * rede / HTTP / autenticacao.
     */
    Optional<BarcodeLookupResult> lookup(String barcode) throws BarcodeLookupException;
}
