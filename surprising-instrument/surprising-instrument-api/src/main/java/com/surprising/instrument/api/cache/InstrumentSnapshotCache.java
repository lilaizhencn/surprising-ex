package com.surprising.instrument.api.cache;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentSpecKey;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内完整合约快照缓存。
 *
 * <p>快照以整体引用方式替换，读取线程不会看到字段、风险档位和指数源的半成品状态。</p>
 */
public final class InstrumentSnapshotCache {

    private final AtomicReference<State> state = new AtomicReference<>(
            new State(Map.of(), Map.of(), Map.of(), false, java.util.Set.of()));

    public void replace(ProductLine productLine, List<InstrumentResponse> instruments) {
        replace(productLine, instruments, Map.of());
    }

    public void replace(ProductLine productLine,
                        List<InstrumentResponse> instruments,
                        Map<String, Long> assetScales) {
        requireProductLine(productLine);
        Map<InstrumentSpecKey, InstrumentResponse> byVersion = new HashMap<>();
        Map<SymbolKey, InstrumentResponse> current = new HashMap<>();
        for (InstrumentResponse instrument : instruments == null ? List.<InstrumentResponse>of() : instruments) {
            if (instrument == null || instrument.contractType() == null
                    || instrument.contractType().productLine() != productLine) {
                continue;
            }
            InstrumentResponse immutable = InstrumentResponse.immutableCopy(instrument);
            InstrumentSpecKey key = key(productLine, immutable);
            byVersion.put(key, immutable);
            current.merge(new SymbolKey(productLine, immutable.symbol()), immutable,
                    InstrumentSnapshotCache::newer);
        }
        state.updateAndGet(previous -> {
            Map<InstrumentSpecKey, InstrumentResponse> mergedVersions = new HashMap<>(previous.byVersion());
            Map<SymbolKey, InstrumentResponse> mergedCurrent = new HashMap<>(previous.current());
            mergedVersions.keySet().removeIf(key -> key.productLine() == productLine);
            mergedCurrent.keySet().removeIf(key -> key.productLine() == productLine);
            mergedVersions.putAll(byVersion);
            mergedCurrent.putAll(current);
            java.util.Set<ProductLine> initialized = new java.util.HashSet<>(previous.initializedProductLines());
            initialized.add(productLine);
            Map<ProductLine, Map<String, Long>> mergedScales = new HashMap<>(previous.assetScales());
            mergedScales.put(productLine, normalizeScales(assetScales));
            return new State(Map.copyOf(mergedVersions), Map.copyOf(mergedCurrent),
                    Map.copyOf(mergedScales), true,
                    java.util.Set.copyOf(initialized));
        });
    }

    public boolean apply(InstrumentEvent event) {
        if (event == null || event.snapshot() == null) {
            return false;
        }
        if (event.symbol() == null || !normalize(event.symbol()).equals(normalize(event.snapshot().symbol()))
                || event.version() != event.snapshot().version()) {
            return false;
        }
        ProductLine productLine = event.productLine();
        if (productLine == null || event.snapshot().contractType() == null
                || event.snapshot().contractType().productLine() != productLine) {
            return false;
        }
        InstrumentResponse immutable = InstrumentResponse.immutableCopy(event.snapshot());
        InstrumentSpecKey versionKey = key(productLine, immutable);
        SymbolKey symbolKey = new SymbolKey(productLine, immutable.symbol());
        state.updateAndGet(previous -> {
            InstrumentResponse oldVersion = previous.byVersion().get(versionKey);
            if (oldVersion != null && oldVersion.equals(immutable)) {
                return previous;
            }
            Map<InstrumentSpecKey, InstrumentResponse> byVersion = new HashMap<>(previous.byVersion());
            Map<SymbolKey, InstrumentResponse> current = new HashMap<>(previous.current());
            byVersion.put(versionKey, immutable);
            current.merge(symbolKey, immutable, InstrumentSnapshotCache::newer);
            java.util.Set<ProductLine> initialized = new java.util.HashSet<>(previous.initializedProductLines());
            initialized.add(productLine);
            return new State(Map.copyOf(byVersion), Map.copyOf(current), previous.assetScales(), true,
                    java.util.Set.copyOf(initialized));
        });
        return true;
    }

    public Optional<InstrumentResponse> current(ProductLine productLine, String symbol) {
        return Optional.ofNullable(state.get().current().get(new SymbolKey(productLine, normalize(symbol))));
    }

    public Optional<InstrumentResponse> version(ProductLine productLine, String symbol, long version) {
        if (version <= 0L) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.get().byVersion().get(
                new InstrumentSpecKey(productLine, normalize(symbol), version)));
    }

    public List<InstrumentResponse> current(ProductLine productLine) {
        List<InstrumentResponse> result = new ArrayList<>();
        state.get().current().forEach((key, value) -> {
            if (key.productLine() == productLine) {
                result.add(value);
            }
        });
        return result.stream().sorted(java.util.Comparator.comparing(InstrumentResponse::symbol)).toList();
    }

    public boolean ready(ProductLine productLine) {
        return initialized(productLine) && !current(productLine).isEmpty();
    }

    public boolean initialized(ProductLine productLine) {
        State currentState = state.get();
        return currentState.ready() && currentState.initializedProductLines().contains(productLine);
    }

    public int size(ProductLine productLine) {
        return current(productLine).size();
    }

    public Optional<Long> scale(ProductLine productLine, String asset) {
        if (productLine == null || asset == null || asset.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.get().assetScales().getOrDefault(productLine, Map.of())
                .get(asset.trim().toUpperCase(java.util.Locale.ROOT)));
    }

    private static InstrumentResponse newer(InstrumentResponse left, InstrumentResponse right) {
        if (right.version() > left.version()) {
            return right;
        }
        if (right.version() < left.version()) {
            return left;
        }
        return compare(right.updatedAt(), left.updatedAt()) > 0 ? right : left;
    }

    private static int compare(Instant left, Instant right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static InstrumentSpecKey key(ProductLine productLine, InstrumentResponse value) {
        return new InstrumentSpecKey(productLine, normalize(value.symbol()), value.version());
    }

    private static void requireProductLine(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
    }

    private static Map<String, Long> normalizeScales(Map<String, Long> scales) {
        if (scales == null || scales.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> normalized = new HashMap<>();
        scales.forEach((asset, scale) -> {
            if (asset != null && !asset.isBlank() && scale != null && scale > 0L) {
                normalized.put(asset.trim().toUpperCase(java.util.Locale.ROOT), scale);
            }
        });
        return Map.copyOf(normalized);
    }

    private static String normalize(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private record State(Map<InstrumentSpecKey, InstrumentResponse> byVersion,
                         Map<SymbolKey, InstrumentResponse> current,
                         Map<ProductLine, Map<String, Long>> assetScales,
                         boolean ready,
                         java.util.Set<ProductLine> initializedProductLines) {
    }

    private record SymbolKey(ProductLine productLine, String symbol) {
    }
}
