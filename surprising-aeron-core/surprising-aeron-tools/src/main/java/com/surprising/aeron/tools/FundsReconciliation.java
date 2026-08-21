package com.surprising.aeron.tools;

import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreRiskQueryCodec;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

final class FundsReconciliation {

    private static final byte[] EMPTY_HASH = new byte[32];
    private static final Set<Metric> ACCOUNT_METRICS = EnumSet.of(
            Metric.AVAILABLE, Metric.LOCKED, Metric.RESERVATION,
            Metric.POSITION_QUANTITY, Metric.POSITION_MARGIN, Metric.REALIZED_PNL);
    private static final Set<Metric> TREASURY_METRICS = EnumSet.of(
            Metric.TREASURY_FEES, Metric.TREASURY_INSURANCE, Metric.TREASURY_DEFICIT);
    private static final Set<Metric> FLOW_METRICS = EnumSet.of(
            Metric.FEE, Metric.FUNDING, Metric.LIQUIDATION, Metric.INSURANCE, Metric.ADL);

    private FundsReconciliation() {
    }

    static Result reconcile(Config config, QueryGateway gateway) {
        if (config == null || gateway == null) throw new IllegalArgumentException("config and gateway are required");
        Progress progress = Progress.load(config);
        if (progress.phase == Phase.DONE) return progress.result(config);

        processAccounts(config, gateway, progress, Role.USER, config.users);
        processAccounts(config, gateway, progress, Role.MAKER, config.makers);
        if (progress.phase == Phase.TREASURY) processTreasury(config, gateway, progress);
        if (progress.phase == Phase.INSURANCE) {
            processLiquidation(config, gateway, progress, CoreLiquidationWorkView.Purpose.INSURANCE);
        }
        if (progress.phase == Phase.ADL) {
            processLiquidation(config, gateway, progress, CoreLiquidationWorkView.Purpose.ADL);
        }

        validateFinal(config, progress);
        progress.phase = Phase.DONE;
        progress.save(config);
        return progress.result(config);
    }

    private static void processAccounts(Config config, QueryGateway gateway, Progress progress,
                                        Role role, UserRanges ranges) {
        Phase wanted = role == Role.USER ? Phase.USERS : Phase.MAKERS;
        if (progress.phase.ordinal() > wanted.ordinal()) return;
        if (progress.phase != wanted) throw new IllegalStateException("invalid reconciliation phase " + progress.phase);
        long index = role == Role.USER ? progress.userIndex : progress.makerIndex;
        Iterator<Long> users = ranges.iterator(index);
        while (users.hasNext()) {
            long userId = users.next();
            CoreResponse userResponse = checked(gateway, progress, CoreMessageType.USER_STATE_QUERY, userId, new byte[0]);
            CoreUserStateView state = CoreStateQueryCodec.decodeUserState(userResponse.data());
            if (state.productLine() != ProductLine.LINEAR_PERPETUAL || state.userId() != userId) {
                throw new IllegalStateException("user state identity mismatch user=" + userId);
            }
            CoreResponse riskResponse = checked(gateway, progress, CoreMessageType.RISK_STATE_QUERY, userId, new byte[0]);
            CoreRiskQueryCodec.decode(riskResponse.data());
            validateAccount(config, progress, role, state);
            progress.stateHash = chained(progress.stateHash, canonicalQuery(role.name(), userId, userResponse.data()));
            progress.stateHash = chained(progress.stateHash, canonicalQuery("RISK", userId, riskResponse.data()));
            index = Math.incrementExact(index);
            if (role == Role.USER) {
                progress.userIndex = index;
            } else {
                progress.makerIndex = index;
            }
            progress.save(config);
        }
        progress.phase = role == Role.USER ? Phase.MAKERS : Phase.TREASURY;
        progress.save(config);
    }

    private static void validateAccount(Config config, Progress progress, Role role, CoreUserStateView state) {
        Map<StateKey, Long> actual = new HashMap<>();
        for (var balance : state.balances()) {
            String asset = normalized(balance.asset(), "balance asset");
            requireExpectedAsset(config, asset);
            if (balance.availableUnits() < 0 || balance.lockedUnits() < 0) {
                throw new IllegalStateException("negative balance user=" + state.userId() + " asset=" + asset);
            }
            putUnique(actual, new StateKey(role, state.userId(), asset, "-", Metric.AVAILABLE),
                    balance.availableUnits());
            putUnique(actual, new StateKey(role, state.userId(), asset, "-", Metric.LOCKED),
                    balance.lockedUnits());
            merge(progress.funds, asset, Math.addExact(balance.availableUnits(), balance.lockedUnits()));
        }
        for (var reservation : state.reservations()) {
            String asset = normalized(reservation.asset(), "reservation asset");
            requireExpectedAsset(config, asset);
            if (reservation.reservedUnits() < 0 || reservation.releasedUnits() < 0
                    || reservation.consumedUnits() < 0) {
                throw new IllegalStateException("negative reservation order=" + reservation.orderId());
            }
            long remaining = Math.subtractExact(Math.subtractExact(
                    reservation.reservedUnits(), reservation.releasedUnits()), reservation.consumedUnits());
            if (remaining < 0) throw new IllegalStateException("over-released reservation order=" + reservation.orderId());
            merge(actual, new StateKey(role, state.userId(), asset, "-", Metric.RESERVATION), remaining);
        }
        for (var position : state.positions()) {
            String asset = normalized(position.marginAsset(), "position asset");
            String symbol = normalized(position.symbol(), "position symbol");
            requireExpectedAsset(config, asset);
            merge(actual, new StateKey(role, state.userId(), asset, symbol, Metric.POSITION_QUANTITY),
                    position.signedQuantitySteps());
            merge(actual, new StateKey(role, state.userId(), asset, symbol, Metric.POSITION_MARGIN),
                    position.positionMarginUnits());
            merge(actual, new StateKey(role, state.userId(), asset, symbol, Metric.REALIZED_PNL),
                    position.realizedPnlUnits());
        }
        compareExact("account user=" + state.userId() + " role=" + role,
                config.ledger.accountState(role, state.userId()), actual);
    }

    private static void processTreasury(Config config, QueryGateway gateway, Progress progress) {
        CoreResponse response = checked(gateway, progress, CoreMessageType.TREASURY_STATE_QUERY, 0, new byte[0]);
        Map<StateKey, Long> actual = new HashMap<>();
        for (var treasury : CoreStateQueryCodec.decodeTreasuryState(response.data())) {
            String asset = normalized(treasury.asset(), "treasury asset");
            requireExpectedAsset(config, asset);
            putUnique(actual, StateKey.treasury(asset, Metric.TREASURY_FEES), treasury.feeBalanceUnits());
            putUnique(actual, StateKey.treasury(asset, Metric.TREASURY_INSURANCE), treasury.insuranceBalanceUnits());
            putUnique(actual, StateKey.treasury(asset, Metric.TREASURY_DEFICIT), treasury.insuranceDeficitUnits());
            long economic = Math.subtractExact(Math.addExact(
                    treasury.feeBalanceUnits(), treasury.insuranceBalanceUnits()), treasury.insuranceDeficitUnits());
            merge(progress.funds, asset, economic);
        }
        compareExact("Treasury", config.ledger.treasuryState(), actual);
        progress.stateHash = chained(progress.stateHash, canonicalQuery("TREASURY", 0, response.data()));
        progress.phase = Phase.INSURANCE;
        progress.save(config);
    }

    private static void processLiquidation(Config config, QueryGateway gateway, Progress progress,
                                           CoreLiquidationWorkView.Purpose purpose) {
        if (!progress.liquidationPurposeStarted) {
            progress.stateHash = chained(progress.stateHash,
                    canonicalQuery("LIQUIDATION_PURPOSE", purpose.ordinal(), new byte[0]));
            progress.liquidationPurposeStarted = true;
            progress.save(config);
        }
        while (true) {
            if (progress.liquidationPages >= config.maxLiquidationPages) {
                throw new IllegalStateException("liquidation pagination exceeded max pages="
                        + config.maxLiquidationPages);
            }
            byte[] payload = CoreLiquidationWorkCodec.encodeQuery(config.productLine, purpose,
                    progress.liquidationCursor, config.liquidationPageSize, 1_048_576);
            CoreResponse response = checked(gateway, progress, CoreMessageType.LIQUIDATION_WORK_QUERY, 0, payload);
            CoreLiquidationWorkView page = CoreLiquidationWorkCodec.decodeWork(response.data());
            if (page.productLine() != ProductLine.LINEAR_PERPETUAL) {
                throw new IllegalStateException("liquidation product line mismatch");
            }
            if (!page.actions().isEmpty() || page.riskScanPending()) {
                throw new IllegalStateException("resolution query returned unrelated liquidation work purpose="
                        + purpose);
            }
            if (!page.complete() && page.nextCursorLiquidationId() <= progress.liquidationCursor) {
                throw new IllegalStateException("liquidation cursor did not advance purpose=" + purpose
                        + " cursor=" + progress.liquidationCursor);
            }
            long lastResolutionId = progress.liquidationCursor;
            for (var resolution : page.resolutions()) {
                if (resolution.purpose() != purpose || resolution.liquidationId() <= lastResolutionId) {
                    throw new IllegalStateException("invalid or repeated liquidation resolution id="
                            + resolution.liquidationId());
                }
                lastResolutionId = resolution.liquidationId();
                String asset = normalized(resolution.asset(), "liquidation asset");
                requireExpectedAsset(config, asset);
                Metric metric = purpose == CoreLiquidationWorkView.Purpose.INSURANCE
                        ? Metric.LIQUIDATION_INSURANCE : Metric.LIQUIDATION_ADL;
                merge(progress.liquidation, new StateKey(Role.TREASURY, 0, asset, "-", metric),
                        resolution.deficitUnits());
                merge(progress.funds, asset, resolution.deficitUnits());
                progress.stateHash = chained(progress.stateHash, CoreLiquidationWorkCodec.encodeWork(
                        new CoreLiquidationWorkView(ProductLine.LINEAR_PERPETUAL, 0, true, null,
                                List.of(), List.of(resolution))));
            }
            if (page.nextCursorLiquidationId() < lastResolutionId) {
                throw new IllegalStateException("liquidation cursor precedes page data purpose=" + purpose);
            }
            progress.liquidationPages = Math.incrementExact(progress.liquidationPages);
            progress.liquidationCursor = page.nextCursorLiquidationId();
            progress.save(config);
            if (page.complete()) break;
        }
        progress.liquidationCursor = 0;
        progress.liquidationPurposeStarted = false;
        progress.phase = purpose == CoreLiquidationWorkView.Purpose.INSURANCE ? Phase.ADL : Phase.VALIDATE;
        progress.save(config);
    }

    private static CoreResponse checked(QueryGateway gateway, Progress progress, CoreMessageType type,
                                        long userId, byte[] payload) {
        CoreResponse response = gateway.query(type, userId, payload);
        if (response == null || response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(type + " failed user=" + userId + " result="
                    + (response == null ? "null" : response.resultCode()));
        }
        if (progress.coreHashSeen && progress.coreStateHash != response.stateHash()) {
            throw new IllegalStateException("Core state hash changed during checkpoint expected="
                    + progress.coreStateHash + " actual=" + response.stateHash());
        }
        progress.coreHashSeen = true;
        progress.coreStateHash = response.stateHash();
        return response;
    }

    private static void validateFinal(Config config, Progress progress) {
        if (progress.phase != Phase.VALIDATE) throw new IllegalStateException("reconciliation did not reach validation");
        compareExact("liquidation", config.ledger.liquidationState(), progress.liquidation);
        for (String asset : config.ledger.assets) {
            long expected = config.ledger.expectedFunds(asset);
            long actual = progress.funds.getOrDefault(asset, 0L);
            long difference = Math.subtractExact(actual, expected);
            if (difference != 0) {
                throw new IllegalStateException("funds mismatch asset=" + asset + " difference=" + difference);
            }
        }
        Set<String> actualAssets = new TreeSet<>(progress.funds.keySet());
        if (!actualAssets.equals(config.ledger.assets)) {
            throw new IllegalStateException("unexpected assets expected=" + config.ledger.assets
                    + " actual=" + actualAssets);
        }
        config.ledger.requireFlowConservation();
    }

    private static void requireExpectedAsset(Config config, String asset) {
        if (!config.ledger.assets.contains(asset)) {
            throw new IllegalStateException("unexpected assets expected=" + config.ledger.assets + " actual=" + asset);
        }
    }

    private static void compareExact(String label, Map<StateKey, Long> expected, Map<StateKey, Long> actual) {
        Set<StateKey> keys = new TreeSet<>();
        keys.addAll(expected.keySet());
        keys.addAll(actual.keySet());
        for (StateKey key : keys) {
            long expectedValue = expected.getOrDefault(key, 0L);
            long actualValue = actual.getOrDefault(key, 0L);
            long difference = Math.subtractExact(actualValue, expectedValue);
            if (difference != 0) {
                throw new IllegalStateException(label + " metric=" + key.metric + " asset=" + key.asset
                        + " symbol=" + key.symbol + " difference=" + difference);
            }
        }
    }

    private static byte[] canonicalQuery(String type, long id, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Integer.BYTES + name.length + Long.BYTES + Integer.BYTES + data.length)
                .putInt(name.length).put(name).putLong(id).putInt(data.length).put(data).array();
    }

    private static byte[] chained(byte[] previous, byte[] value) {
        MessageDigest digest = sha256();
        digest.update(previous);
        digest.update(value);
        return digest.digest();
    }

    private static String hashFunds(Map<String, Long> funds) {
        MessageDigest digest = sha256();
        new TreeMap<>(funds).forEach((asset, amount) -> {
            byte[] bytes = asset.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(amount).array());
        });
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static byte[] unhex(String value) {
        byte[] bytes = java.util.HexFormat.of().parseHex(value);
        if (bytes.length != EMPTY_HASH.length) throw new IllegalArgumentException("invalid checkpoint hash");
        return bytes;
    }

    private static String normalized(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static <K> void merge(Map<K, Long> values, K key, long amount) {
        values.merge(key, amount, Math::addExact);
    }

    private static <K> void putUnique(Map<K, Long> values, K key, long amount) {
        if (values.putIfAbsent(key, amount) != null) throw new IllegalStateException("duplicate state key " + key);
    }

    interface QueryGateway {
        CoreResponse query(CoreMessageType type, long userId, byte[] payload);
    }

    record Config(ProductLine productLine, UserRanges users, UserRanges makers, Ledger ledger,
                  int liquidationPageSize, int maxLiquidationPages, Path checkpoint) {
        Config {
            if (productLine != ProductLine.LINEAR_PERPETUAL) {
                throw new IllegalArgumentException("funds reconciliation requires LINEAR_PERPETUAL");
            }
            if (users == null || makers == null || ledger == null) {
                throw new IllegalArgumentException("ranges and ledger are required");
            }
            if (liquidationPageSize < 1 || liquidationPageSize > 1_000 || maxLiquidationPages < 1) {
                throw new IllegalArgumentException("invalid liquidation bounds");
            }
            if (users.overlaps(makers)) throw new IllegalArgumentException("user/maker overlap");
            ledger.requireCoverage(Role.USER, users);
            ledger.requireCoverage(Role.MAKER, makers);
        }

        String fingerprint() {
            MessageDigest digest = sha256();
            digest.update(productLine.name().getBytes(StandardCharsets.UTF_8));
            digest.update(users.canonical().getBytes(StandardCharsets.UTF_8));
            digest.update(makers.canonical().getBytes(StandardCharsets.UTF_8));
            digest.update(ledger.fingerprint.getBytes(StandardCharsets.UTF_8));
            digest.update(ByteBuffer.allocate(Integer.BYTES * 2)
                    .putInt(liquidationPageSize).putInt(maxLiquidationPages).array());
            return hex(digest.digest());
        }
    }

    record Result(long userCount, long makerCount, int liquidationPages, long coreStateHash,
                  String stateHash, String fundsHash, long fundsDifference, Set<String> assets) {
    }

    enum Role {
        USER,
        MAKER,
        TREASURY
    }

    enum Metric {
        AVAILABLE,
        LOCKED,
        RESERVATION,
        POSITION_QUANTITY,
        POSITION_MARGIN,
        REALIZED_PNL,
        TREASURY_FEES,
        TREASURY_INSURANCE,
        TREASURY_DEFICIT,
        LIQUIDATION_INSURANCE,
        LIQUIDATION_ADL,
        FEE,
        FUNDING,
        LIQUIDATION,
        INSURANCE,
        ADL
    }

    enum Phase {
        USERS,
        MAKERS,
        TREASURY,
        INSURANCE,
        ADL,
        VALIDATE,
        DONE
    }

    record StateKey(Role role, long userId, String asset, String symbol, Metric metric)
            implements Comparable<StateKey> {
        static StateKey treasury(String asset, Metric metric) {
            return new StateKey(Role.TREASURY, 0, asset, "-", metric);
        }

        @Override
        public int compareTo(StateKey other) {
            int value = role.compareTo(other.role);
            if (value == 0) value = Long.compare(userId, other.userId);
            if (value == 0) value = asset.compareTo(other.asset);
            if (value == 0) value = symbol.compareTo(other.symbol);
            if (value == 0) value = metric.compareTo(other.metric);
            return value;
        }

        String encoded() {
            return role + "|" + userId + "|" + asset + "|" + symbol + "|" + metric;
        }

        static StateKey decode(String value) {
            String[] fields = value.split("\\|", -1);
            if (fields.length != 5) throw new IllegalArgumentException("invalid checkpoint state key");
            return new StateKey(Role.valueOf(fields[0]), Long.parseLong(fields[1]), fields[2], fields[3],
                    Metric.valueOf(fields[4]));
        }
    }

    private record AccountKey(Role role, long userId) {
    }

    static final class Ledger {
        private final Map<StateKey, Long> values;
        private final Map<AccountKey, Map<StateKey, Long>> accounts;
        private final Set<AccountKey> coveredAccounts;
        private final Set<String> assets;
        private final String fingerprint;

        private Ledger(Map<StateKey, Long> values) {
            this.values = Map.copyOf(values);
            Map<AccountKey, Map<StateKey, Long>> accountIndex = new HashMap<>();
            Set<AccountKey> coverage = new TreeSet<>((left, right) -> {
                int value = left.role.compareTo(right.role);
                return value == 0 ? Long.compare(left.userId, right.userId) : value;
            });
            values.forEach((key, amount) -> {
                if (key.role != Role.TREASURY) coverage.add(new AccountKey(key.role, key.userId));
                if (ACCOUNT_METRICS.contains(key.metric)) {
                    accountIndex.computeIfAbsent(new AccountKey(key.role, key.userId), ignored -> new HashMap<>())
                            .put(key, amount);
                }
            });
            Map<AccountKey, Map<StateKey, Long>> immutableIndex = new HashMap<>();
            accountIndex.forEach((key, state) -> immutableIndex.put(key, Map.copyOf(state)));
            this.accounts = Map.copyOf(immutableIndex);
            this.coveredAccounts = Set.copyOf(coverage);
            TreeSet<String> foundAssets = new TreeSet<>();
            values.keySet().forEach(key -> foundAssets.add(key.asset));
            this.assets = Set.copyOf(foundAssets);
            MessageDigest digest = sha256();
            new TreeMap<>(values).forEach((key, amount) -> {
                digest.update(key.encoded().getBytes(StandardCharsets.UTF_8));
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(amount).array());
            });
            this.fingerprint = hex(digest.digest());
        }

        static Ledger read(Path path) {
            if (path == null) throw new IllegalArgumentException("ledger path is required");
            Map<StateKey, Long> values = new HashMap<>();
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    parseLine(values, line, lineNumber);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("failed to read ledger " + path, exception);
            }
            return completed(values);
        }

        static Ledger parse(List<String> lines) {
            if (lines == null) throw new IllegalArgumentException("ledger lines are required");
            Map<StateKey, Long> values = new HashMap<>();
            for (int index = 0; index < lines.size(); index++) parseLine(values, lines.get(index), index + 1);
            return completed(values);
        }

        private static Ledger completed(Map<StateKey, Long> values) {
            if (values.isEmpty()) throw new IllegalArgumentException("ledger is empty");
            return new Ledger(values);
        }

        private static void parseLine(Map<StateKey, Long> values, String line, int lineNumber) {
            if (line == null || line.isBlank()) throw malformed(lineNumber, "blank row");
            String[] fields = line.split("\\t", -1);
            if (fields.length != 7) throw malformed(lineNumber, "expected 7 tab-separated fields");
            try {
                String kind = fields[0].trim().toUpperCase(Locale.ROOT);
                if (!kind.equals("SEED") && !kind.equals("OPERATION")) {
                    throw malformed(lineNumber, "kind must be SEED or OPERATION");
                }
                Role role = Role.valueOf(fields[1].trim().toUpperCase(Locale.ROOT));
                long userId = Long.parseLong(fields[2]);
                if ((role == Role.TREASURY) != (userId == 0) || userId < 0) {
                    throw malformed(lineNumber, "invalid role/userId");
                }
                String asset = normalized(fields[3], "asset");
                String symbol = fields[4].equals("-") ? "-" : normalized(fields[4], "symbol");
                Metric metric = Metric.valueOf(fields[5].trim().toUpperCase(Locale.ROOT));
                long delta = Long.parseLong(fields[6]);
                validateMetric(role, symbol, metric, lineNumber);
                merge(values, new StateKey(role, userId, asset, symbol, metric), delta);
            } catch (IllegalArgumentException exception) {
                if (exception.getMessage() != null && exception.getMessage().startsWith("invalid ledger line")) {
                    throw exception;
                }
                throw malformed(lineNumber, exception.getMessage());
            }
        }

        private static void validateMetric(Role role, String symbol, Metric metric, int lineNumber) {
            if (ACCOUNT_METRICS.contains(metric) && role == Role.TREASURY) {
                throw malformed(lineNumber, "account metric requires USER or MAKER");
            }
            if ((metric == Metric.POSITION_QUANTITY || metric == Metric.POSITION_MARGIN
                    || metric == Metric.REALIZED_PNL) == symbol.equals("-")) {
                throw malformed(lineNumber, "position metrics require a symbol and other metrics require '-'");
            }
            if ((TREASURY_METRICS.contains(metric) || metric == Metric.LIQUIDATION_INSURANCE
                    || metric == Metric.LIQUIDATION_ADL) && role != Role.TREASURY) {
                throw malformed(lineNumber, "Treasury metric requires TREASURY role");
            }
        }

        private static IllegalArgumentException malformed(int lineNumber, String reason) {
            return new IllegalArgumentException("invalid ledger line " + lineNumber + ": " + reason);
        }

        Map<StateKey, Long> accountState(Role role, long userId) {
            return accounts.getOrDefault(new AccountKey(role, userId), Map.of());
        }

        Map<StateKey, Long> treasuryState() {
            Map<StateKey, Long> result = new HashMap<>();
            values.forEach((key, amount) -> {
                if (TREASURY_METRICS.contains(key.metric)) result.put(key, amount);
            });
            return result;
        }

        Map<StateKey, Long> liquidationState() {
            Map<StateKey, Long> result = new HashMap<>();
            values.forEach((key, amount) -> {
                if (key.metric == Metric.LIQUIDATION_INSURANCE || key.metric == Metric.LIQUIDATION_ADL) {
                    result.put(key, amount);
                }
            });
            return result;
        }

        long expectedFunds(String asset) {
            long total = 0;
            for (var entry : values.entrySet()) {
                StateKey key = entry.getKey();
                if (!key.asset.equals(asset)) continue;
                long amount = entry.getValue();
                if (key.metric == Metric.AVAILABLE || key.metric == Metric.LOCKED
                        || key.metric == Metric.TREASURY_FEES || key.metric == Metric.TREASURY_INSURANCE
                        || key.metric == Metric.LIQUIDATION_INSURANCE || key.metric == Metric.LIQUIDATION_ADL) {
                    total = Math.addExact(total, amount);
                } else if (key.metric == Metric.TREASURY_DEFICIT) {
                    total = Math.subtractExact(total, amount);
                }
            }
            return total;
        }

        void requireCoverage(Role role, UserRanges ranges) {
            long covered = 0;
            for (AccountKey account : coveredAccounts) {
                if (account.role != role) continue;
                if (!ranges.contains(account.userId)) {
                    throw new IllegalArgumentException("ledger has out-of-range " + role + " user=" + account.userId);
                }
                covered = Math.incrementExact(covered);
            }
            if (covered != ranges.size) {
                throw new IllegalArgumentException("ledger missing " + role + " accounts expected="
                        + ranges.size + " actual=" + covered);
            }
        }

        void requireFlowConservation() {
            Map<String, Long> flows = new HashMap<>();
            values.forEach((key, amount) -> {
                if (FLOW_METRICS.contains(key.metric)) merge(flows, key.asset + '|' + key.metric, amount);
            });
            flows.forEach((key, amount) -> {
                if (amount != 0) throw new IllegalStateException("ledger flow is not conserved " + key
                        + " difference=" + amount);
            });
        }
    }

    static final class UserRanges implements Iterable<Long> {
        private final List<Range> ranges;
        private final long size;

        private UserRanges(List<Range> ranges) {
            this.ranges = List.copyOf(ranges);
            long count = 0;
            for (Range range : ranges) count = Math.addExact(count, Math.subtractExact(range.endExclusive, range.start));
            this.size = count;
        }

        static UserRanges parse(String configured) {
            if (configured == null || configured.isBlank()) return new UserRanges(List.of());
            List<Range> ranges = new ArrayList<>();
            for (String value : configured.split(",", -1)) {
                String[] fields = value.trim().split(":", -1);
                if (fields.length != 2) throw new IllegalArgumentException("invalid user range: " + value);
                long start;
                long end;
                try {
                    start = Long.parseLong(fields[0]);
                    end = Long.parseLong(fields[1]);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("invalid user range: " + value, exception);
                }
                if (start <= 0 || end <= start) throw new IllegalArgumentException("invalid user range: " + value);
                ranges.add(new Range(start, end));
            }
            ranges.sort((left, right) -> Long.compare(left.start, right.start));
            for (int index = 1; index < ranges.size(); index++) {
                if (ranges.get(index).start < ranges.get(index - 1).endExclusive) {
                    throw new IllegalArgumentException("overlapping user ranges");
                }
            }
            return new UserRanges(ranges);
        }

        long size() {
            return size;
        }

        Stream<Long> stream() {
            return StreamSupport.stream(spliterator(), false);
        }

        boolean overlaps(UserRanges other) {
            int left = 0;
            int right = 0;
            while (left < ranges.size() && right < other.ranges.size()) {
                Range a = ranges.get(left);
                Range b = other.ranges.get(right);
                if (a.start < b.endExclusive && b.start < a.endExclusive) return true;
                if (a.endExclusive <= b.start) left++; else right++;
            }
            return false;
        }

        boolean contains(long userId) {
            int low = 0;
            int high = ranges.size() - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                Range range = ranges.get(middle);
                if (userId < range.start) {
                    high = middle - 1;
                } else if (userId >= range.endExclusive) {
                    low = middle + 1;
                } else {
                    return true;
                }
            }
            return false;
        }

        String canonical() {
            return ranges.stream().map(range -> range.start + ":" + range.endExclusive)
                    .reduce((left, right) -> left + ',' + right).orElse("");
        }

        @Override
        public Iterator<Long> iterator() {
            return iterator(0);
        }

        Iterator<Long> iterator(long skip) {
            if (skip < 0 || skip > size) throw new IllegalArgumentException("invalid range offset " + skip);
            return new Iterator<>() {
                private int rangeIndex;
                private long current = ranges.isEmpty() ? 0 : ranges.getFirst().start;
                private long remainingSkip = skip;

                {
                    while (rangeIndex < ranges.size() && remainingSkip > 0) {
                        Range range = ranges.get(rangeIndex);
                        long available = Math.subtractExact(range.endExclusive, current);
                        if (remainingSkip < available) {
                            current = Math.addExact(current, remainingSkip);
                            remainingSkip = 0;
                        } else {
                            remainingSkip -= available;
                            rangeIndex++;
                            if (rangeIndex < ranges.size()) current = ranges.get(rangeIndex).start;
                        }
                    }
                }

                @Override
                public boolean hasNext() {
                    return rangeIndex < ranges.size();
                }

                @Override
                public Long next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    long value = current;
                    Range range = ranges.get(rangeIndex);
                    current = Math.incrementExact(current);
                    if (current == range.endExclusive) {
                        rangeIndex++;
                        if (rangeIndex < ranges.size()) current = ranges.get(rangeIndex).start;
                    }
                    return value;
                }
            };
        }

        @Override
        public Spliterator<Long> spliterator() {
            return Spliterators.spliterator(iterator(), size,
                    Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.IMMUTABLE | Spliterator.NONNULL);
        }

        private record Range(long start, long endExclusive) {
        }
    }

    private static final class Progress {
        private Phase phase = Phase.USERS;
        private long userIndex;
        private long makerIndex;
        private int liquidationPages;
        private long liquidationCursor;
        private boolean liquidationPurposeStarted;
        private boolean coreHashSeen;
        private long coreStateHash;
        private byte[] stateHash = EMPTY_HASH.clone();
        private final Map<String, Long> funds = new HashMap<>();
        private final Map<StateKey, Long> liquidation = new HashMap<>();

        static Progress load(Config config) {
            if (config.checkpoint == null || !Files.exists(config.checkpoint)) return new Progress();
            Properties values = new Properties();
            try (var reader = Files.newBufferedReader(config.checkpoint, StandardCharsets.UTF_8)) {
                values.load(reader);
            } catch (IOException exception) {
                throw new IllegalStateException("failed to read reconciliation checkpoint", exception);
            }
            if (!config.fingerprint().equals(required(values, "fingerprint"))) {
                throw new IllegalStateException("reconciliation checkpoint configuration changed");
            }
            Progress progress = new Progress();
            progress.phase = Phase.valueOf(required(values, "phase"));
            progress.userIndex = positiveOrZero(values, "userIndex");
            progress.makerIndex = positiveOrZero(values, "makerIndex");
            progress.liquidationPages = Math.toIntExact(positiveOrZero(values, "liquidationPages"));
            progress.liquidationCursor = positiveOrZero(values, "liquidationCursor");
            progress.liquidationPurposeStarted = Boolean.parseBoolean(required(values,
                    "liquidationPurposeStarted"));
            progress.coreHashSeen = Boolean.parseBoolean(required(values, "coreHashSeen"));
            progress.coreStateHash = Long.parseLong(required(values, "coreStateHash"));
            progress.stateHash = unhex(required(values, "stateHash"));
            values.stringPropertyNames().stream().sorted().forEach(name -> {
                if (name.startsWith("fund.")) {
                    progress.funds.put(name.substring(5), Long.parseLong(values.getProperty(name)));
                } else if (name.startsWith("liquidation.")) {
                    progress.liquidation.put(StateKey.decode(name.substring(12)),
                            Long.parseLong(values.getProperty(name)));
                }
            });
            return progress;
        }

        void save(Config config) {
            if (config.checkpoint == null) return;
            Properties values = new Properties();
            values.setProperty("fingerprint", config.fingerprint());
            values.setProperty("phase", phase.name());
            values.setProperty("userIndex", Long.toString(userIndex));
            values.setProperty("makerIndex", Long.toString(makerIndex));
            values.setProperty("liquidationPages", Integer.toString(liquidationPages));
            values.setProperty("liquidationCursor", Long.toString(liquidationCursor));
            values.setProperty("liquidationPurposeStarted", Boolean.toString(liquidationPurposeStarted));
            values.setProperty("coreHashSeen", Boolean.toString(coreHashSeen));
            values.setProperty("coreStateHash", Long.toString(coreStateHash));
            values.setProperty("stateHash", hex(stateHash));
            funds.forEach((asset, amount) -> values.setProperty("fund." + asset, Long.toString(amount)));
            liquidation.forEach((key, amount) ->
                    values.setProperty("liquidation." + key.encoded(), Long.toString(amount)));
            Path absolute = config.checkpoint.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent == null) throw new IllegalArgumentException("checkpoint requires a parent path");
            Path temporary = parent.resolve(absolute.getFileName() + ".tmp");
            try {
                Files.createDirectories(parent);
                try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    values.store(writer, "bounded funds reconciliation checkpoint");
                }
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new IllegalStateException("failed to persist reconciliation checkpoint", exception);
            }
        }

        Result result(Config config) {
            return new Result(config.users.size(), config.makers.size(), liquidationPages, coreStateHash,
                    hex(stateHash), hashFunds(funds), 0, config.ledger.assets);
        }

        private static String required(Properties values, String name) {
            String value = values.getProperty(name);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("checkpoint missing " + name);
            return value;
        }

        private static long positiveOrZero(Properties values, String name) {
            long value = Long.parseLong(required(values, name));
            if (value < 0) throw new IllegalArgumentException("checkpoint " + name + " is negative");
            return value;
        }
    }
}
