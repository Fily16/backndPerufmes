package org.example.backendbvaberiaperfumes.service.nso;

import org.example.backendbvaberiaperfumes.model.AppConfig;
import org.example.backendbvaberiaperfumes.model.Product;
import org.example.backendbvaberiaperfumes.repository.AppConfigRepository;
import org.example.backendbvaberiaperfumes.repository.NsoRecordRepository;
import org.example.backendbvaberiaperfumes.repository.ProductNsoRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Filtro publico NSO ("solo se muestra y se compra lo que tiene Notificacion Sanitaria").
 *
 * Gate efectivo = app_config nso_gate_enabled == "true" Y hay al menos un NsoRecord activo (sin lista cargada
 * la tienda quedaria vacia, asi que el filtro no aplica). Nace apagado: con el gate inactivo todo se comporta
 * EXACTAMENTE como antes (isPurchasable true, filterPublic devuelve la misma lista).
 *
 * Visible con gate activo: !archived && available && CON_NSO con codigo activo (y de Peru si
 * nso_accept_can_codes == "false"). Cache de 30 s para no consultar la BD en cada request del catalogo;
 * NsoService llama invalidate() tras cada cambio (tambien se re-invalida al terminar la transaccion).
 */
@Component
public class NsoGate {

    public static final String CFG_GATE = "nso_gate_enabled";
    public static final String CFG_ACCEPT_CAN = "nso_accept_can_codes";
    static final long TTL_MS = 30_000;

    private final AppConfigRepository configRepo;
    private final NsoRecordRepository recordRepo;
    private final ProductNsoRepository productNsoRepo;

    /** Sube en cada invalidate(): un calculo que empezo antes no guarda su valor (evita cachear datos viejos). */
    private final AtomicLong generation = new AtomicLong();
    private volatile Cached<Boolean> activeCache;
    private volatile Cached<Set<Long>> idsCache;

    private record Cached<T>(T value, long expiresAt) {}

    public NsoGate(AppConfigRepository configRepo, NsoRecordRepository recordRepo,
                   ProductNsoRepository productNsoRepo) {
        this.configRepo = configRepo;
        this.recordRepo = recordRepo;
        this.productNsoRepo = productNsoRepo;
    }

    /** Gate efectivo (flag encendido y catalogo con codigos activos). */
    public boolean isActive() {
        Cached<Boolean> c = activeCache;
        long now = System.currentTimeMillis();
        if (c != null && c.expiresAt() > now) return c.value();
        long gen = generation.get();
        boolean value = "true".equalsIgnoreCase(config(CFG_GATE, "false")) && recordRepo.countByActiveTrue() > 0;
        if (generation.get() == gen) activeCache = new Cached<>(value, now + TTL_MS);
        return value;
    }

    /**
     * Ids de productos vigentes CON_NSO con codigo activo (solo PE si nso_accept_can_codes == "false").
     * Se calcula aunque el gate este apagado (sirve para previsualizar).
     */
    public Set<Long> conNsoIds() {
        Cached<Set<Long>> c = idsCache;
        long now = System.currentTimeMillis();
        if (c != null && c.expiresAt() > now) return c.value();
        long gen = generation.get();
        boolean acceptCan = !"false".equalsIgnoreCase(config(CFG_ACCEPT_CAN, "true"));
        List<Long> ids = acceptCan ? productNsoRepo.findEligibleProductIds() : productNsoRepo.findEligiblePeruProductIds();
        Set<Long> value = Collections.unmodifiableSet(new HashSet<>(ids));
        if (generation.get() == gen) idsCache = new Cached<>(value, now + TTL_MS);
        return value;
    }

    /** Visible para el cliente: !archived && available && (gate inactivo || CON_NSO). */
    public boolean isPublic(Product p) {
        if (p == null || Boolean.TRUE.equals(p.getArchived()) || Boolean.FALSE.equals(p.getAvailable())) return false;
        return !isActive() || (p.getId() != null && conNsoIds().contains(p.getId()));
    }

    /** Solo la parte NSO de la visibilidad (para quien ya tiene el id): true con gate inactivo. */
    public boolean isPublicId(Long productId) {
        return !isActive() || (productId != null && conNsoIds().contains(productId));
    }

    /** Se puede volver a comprar/encargar: true con gate inactivo; con gate activo, solo CON_NSO. */
    public boolean isPurchasable(Long productId) {
        return isPublicId(productId);
    }

    /** Gate inactivo: devuelve la MISMA lista (comportamiento de siempre). Activo: solo los isPublic. */
    public List<Product> filterPublic(List<Product> products) {
        if (products == null || !isActive()) return products;
        List<Product> out = new ArrayList<>(products.size());
        for (Product p : products) if (isPublic(p)) out.add(p);
        return out;
    }

    /** Lanza NsoBlockedException (400 para el cliente) si el producto no se puede comprar. */
    public void assertPurchasable(Product product) {
        if (product == null) return;
        assertPurchasable(List.of(product));
    }

    /** Igual que assertPurchasable(Product) pero junta TODOS los bloqueados en un solo error. */
    public void assertPurchasable(Collection<Product> products) {
        if (products == null || products.isEmpty() || !isActive()) return;
        Set<Long> allowed = conNsoIds();
        Map<Long, Product> blocked = new LinkedHashMap<>();
        for (Product p : products) {
            if (p == null || p.getId() == null) continue;
            if (!allowed.contains(p.getId())) blocked.putIfAbsent(p.getId(), p);
        }
        if (blocked.isEmpty()) return;
        throw new NsoBlockedException(blockedMessage(blocked.values()), blocked.keySet());
    }

    /**
     * Mensaje al cliente (NUNCA dice "NSO"):
     * «Marca Nombre» ya no está disponible. Retíralo de tu pedido para continuar.
     */
    public static String blockedMessage(Collection<Product> products) {
        List<String> names = new ArrayList<>();
        for (Product p : products) names.add("«" + displayName(p) + "»");
        if (names.size() == 1) {
            return names.get(0) + " ya no está disponible. Retíralo de tu pedido para continuar.";
        }
        String joined = String.join(", ", names.subList(0, names.size() - 1)) + " y " + names.get(names.size() - 1);
        return joined + " ya no están disponibles. Retíralos de tu pedido para continuar.";
    }

    /** "Marca Nombre" (sin repetir la marca si el nombre ya empieza con ella). Lo usan tambien los avisos del admin. */
    public static String displayName(Product p) {
        String brand = p.getBrand() == null ? "" : p.getBrand().trim();
        String name = p.getName() == null ? "" : p.getName().trim();
        if (brand.isEmpty()) return name;
        if (name.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT))) return name;
        return (brand + " " + name).trim();
    }

    /** Olvida el cache ya y otra vez al terminar la transaccion en curso (si la hay). */
    public void invalidate() {
        clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        clear();
                    }
                });
            } catch (IllegalStateException ignored) {
                // la transaccion ya esta terminando: el clear inmediato basta
            }
        }
    }

    private void clear() {
        generation.incrementAndGet();
        activeCache = null;
        idsCache = null;
    }

    private String config(String key, String fallback) {
        return configRepo.findByConfigKey(key).map(AppConfig::getConfigValue)
                .map(String::trim).filter(v -> !v.isEmpty()).orElse(fallback);
    }
}
