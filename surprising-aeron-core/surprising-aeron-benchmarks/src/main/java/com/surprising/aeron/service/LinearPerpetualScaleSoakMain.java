package com.surprising.aeron.service;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.surprising.aeron.service.LinearPerpetualBenchmarkSupport.Harness;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

public final class LinearPerpetualScaleSoakMain {

    private LinearPerpetualScaleSoakMain() {
    }

    public static void main(String[] args) {
        SoakConfig config = SoakConfig.parse(args);
        LinearPerpetualBenchmarkSupport.configureAccountLanes(4);
        LinearPerpetualScaleConfig scale = LinearPerpetualScaleConfig.scale(
                config.listedSymbols(), config.activeSymbols(), config.maxPositionsPerUser(),
                config.maxOpenOrdersPerUser(), config.trafficProfile(), config.lifecycleSymbolsPerRun());
        long setupStart = System.nanoTime();
        LinearPerpetualMixedWorkload.Template template =
                LinearPerpetualMixedWorkload.template(4, config.activeUsers(), scale);
        long setupNanos = System.nanoTime() - setupStart;
        PrimitiveLatencies latencies = new PrimitiveLatencies();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        BufferPoolMXBean direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> "direct".equals(pool.getName())).findFirst().orElse(null);
        BufferPoolMXBean mapped = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> "mapped".equals(pool.getName())).findFirst().orElse(null);
        var threads = ManagementFactory.getThreadMXBean();
        var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        LeakSamples leakSamples = new LeakSamples();
        long startedAt = System.nanoTime();
        long deadline = Math.addExact(startedAt, java.util.concurrent.TimeUnit.SECONDS.toNanos(config.durationSeconds()));
        long nextSample = Math.addExact(startedAt,
                java.util.concurrent.TimeUnit.SECONDS.toNanos(config.sampleSeconds()));
        long terminalOperations = 0;
        long terminalCoreMessages = 0;
        long sampleOperations = 0;
        long sampleStartedAt = startedAt;
        long maxBacklog = 0;
        long initialHeap = memory.getHeapMemoryUsage().getUsed();
        long maxHeap = initialHeap;
        long initialDirect = direct == null ? 0 : direct.getMemoryUsed();
        long maxDirect = initialDirect;
        long lastGcId = -1;
        long lastHeapUsedAfterGc = -1;
        long lastOldGenerationUsedAfterGc = -1;
        int postGcPointsSinceSample = 0;
        LinearPerpetualWorkloadEvent workloadEvent = new LinearPerpetualWorkloadEvent();
        workloadEvent.activeUsers = config.activeUsers();
        workloadEvent.symbols = config.activeSymbols();
        workloadEvent.listedSymbols = config.listedSymbols();
        workloadEvent.maxPositionsPerUser = config.maxPositionsPerUser();
        workloadEvent.maxOpenOrdersPerUser = config.maxOpenOrdersPerUser();
        workloadEvent.trafficProfile = config.trafficProfile().name();
        workloadEvent.hftRounds = config.hftRounds();
        workloadEvent.hftBatchSize = config.hftBatchSize();
        workloadEvent.lifecycleSymbolsPerRun = config.lifecycleSymbolsPerRun();
        workloadEvent.begin();
        LinearPerpetualBenchmarkSupport.SnapshotTemplate snapshot;
        try (GcCompletionTracker gcTracker = new GcCompletionTracker(collectors);
             LinearPerpetualMixedWorkload.StatefulScenario scenario =
                     LinearPerpetualMixedWorkload.scaleScenario(
                             template, config.hftRounds(), config.hftBatchSize())) {
            while (System.nanoTime() < deadline) {
                long runStart = System.nanoTime();
                scenario.run();
                long runNanos = System.nanoTime() - runStart;
                latencies.add(runNanos);
                terminalOperations = Math.addExact(terminalOperations, scenario.terminalOperations());
                terminalCoreMessages = Math.addExact(terminalCoreMessages, scenario.terminalCoreMessages());
                sampleOperations = Math.addExact(sampleOperations, scenario.terminalOperations());
                maxBacklog = Math.max(maxBacklog, scenario.maxBacklog());
                long now = System.nanoTime();
                for (GcAfterSample gc : gcTracker.drain()) {
                    long directBytes = direct == null ? 0 : direct.getMemoryUsed();
                    long mappedBytes = mapped == null ? 0 : mapped.getMemoryUsed();
                    leakSamples.add((gc.completedAtNanos() - startedAt) / 1_000_000_000.0,
                            gc.signalSequence(), gc.gcId(), gc.heapUsedAfterGc(), gc.oldGenerationUsedAfterGc(), directBytes,
                            mappedBytes, direct == null ? 0 : direct.getCount(),
                            mapped == null ? 0 : mapped.getCount(), threads.getThreadCount(),
                            openFileDescriptors(operatingSystem));
                    lastGcId = gc.gcId();
                    lastHeapUsedAfterGc = gc.heapUsedAfterGc();
                    lastOldGenerationUsedAfterGc = gc.oldGenerationUsedAfterGc();
                    postGcPointsSinceSample++;
                }
                if (now >= nextSample) {
                    long heap = memory.getHeapMemoryUsage().getUsed();
                    long directBytes = direct == null ? 0 : direct.getMemoryUsed();
                    long mappedBytes = mapped == null ? 0 : mapped.getMemoryUsed();
                    boolean postGc = postGcPointsSinceSample > 0;
                    maxHeap = Math.max(maxHeap, heap);
                    maxDirect = Math.max(maxDirect, directBytes);
                    double sampleSeconds = (now - sampleStartedAt) / 1_000_000_000.0;
                    System.out.printf(Locale.ROOT,
                            "{\"type\":\"sample\",\"elapsedSeconds\":%.3f,"
                                    + "\"terminalBusinessOpsPerSec\":%.3f,\"usedHeapBytes\":%d,"
                                    + "\"oldGenerationBytes\":%d,\"directBytes\":%d,"
                                    + "\"mappedBytes\":%d,\"postGc\":%s,"
                                    + "\"postGcPoints\":%d,\"lastGcId\":%d,"
                                    + "\"heapUsedAfterGc\":%d,\"oldGenerationUsedAfterGc\":%d,"
                                    + "\"gcCollections\":%d,\"threadCount\":%d,\"openFileDescriptors\":%d,"
                                    + "\"directBufferCount\":%d,\"mappedBufferCount\":%d,"
                                    + "\"incompleteRiskScans\":%d,"
                                    + "\"incompleteFundingSettlements\":%d}%n",
                            (now - startedAt) / 1_000_000_000.0, sampleOperations / sampleSeconds,
                            heap, lastOldGenerationUsedAfterGc, directBytes, mappedBytes, postGc,
                            postGcPointsSinceSample, lastGcId, lastHeapUsedAfterGc,
                            lastOldGenerationUsedAfterGc, gcTracker.completedCount(),
                            threads.getThreadCount(),
                            openFileDescriptors(operatingSystem), direct == null ? 0 : direct.getCount(),
                            mapped == null ? 0 : mapped.getCount(), scenario.incompleteRiskScans(),
                            scenario.incompleteFundingSettlements());
                    sampleOperations = 0;
                    postGcPointsSinceSample = 0;
                    sampleStartedAt = now;
                    nextSample = Math.addExact(now,
                            java.util.concurrent.TimeUnit.SECONDS.toNanos(config.sampleSeconds()));
                }
            }
            for (GcAfterSample gc : gcTracker.drain()) {
                leakSamples.add((gc.completedAtNanos() - startedAt) / 1_000_000_000.0,
                        gc.signalSequence(), gc.gcId(), gc.heapUsedAfterGc(), gc.oldGenerationUsedAfterGc(),
                        direct == null ? 0 : direct.getMemoryUsed(), mapped == null ? 0 : mapped.getMemoryUsed(),
                        direct == null ? 0 : direct.getCount(), mapped == null ? 0 : mapped.getCount(),
                        threads.getThreadCount(), openFileDescriptors(operatingSystem));
            }
            scenario.verify();
            snapshot = scenario.captureSnapshot();
            long finalHeap = memory.getHeapMemoryUsage().getUsed();
            long finalDirect = direct == null ? 0 : direct.getMemoryUsed();
            maxHeap = Math.max(maxHeap, finalHeap);
            maxDirect = Math.max(maxDirect, finalDirect);
            long restoreStart = System.nanoTime();
            try (Harness restored = Harness.restore(snapshot)) {
                if (restored.state().tradingState().businessStateHash() != snapshot.businessStateHash()) {
                    throw new IllegalStateException("soak snapshot restore changed business state");
                }
            }
            long restoreNanos = System.nanoTime() - restoreStart;
            long elapsedNanos = System.nanoTime() - startedAt;
            LeakVerdict leak = leakSamples.verdict();
            workloadEvent.acceptedBusinessOperations = terminalOperations;
            workloadEvent.terminalBusinessOperations = terminalOperations;
            workloadEvent.acceptedCoreMessages = terminalCoreMessages;
            workloadEvent.terminalCoreMessages = terminalCoreMessages;
            workloadEvent.maxMatchingBacklog = maxBacklog;
            workloadEvent.commit();
            String status = leak.pass() ? "PASS" : "FAIL";
            System.out.printf(Locale.ROOT,
                    "{\"type\":\"summary\",\"status\":\"%s\",\"fundsInvariant\":true,"
                            + "\"activeUsers\":%d,\"listedSymbols\":%d,\"activeSymbols\":%d,"
                            + "\"maxPositionsPerUser\":%d,\"maxOpenOrdersPerUser\":%d,"
                            + "\"trafficProfile\":\"%s\",\"lifecycleSymbolsPerRun\":%d,"
                            + "\"elapsedSeconds\":%.3f,\"setupMillis\":%.3f,"
                            + "\"terminalBusinessOperations\":%d,\"terminalBusinessOpsPerSec\":%.3f,"
                            + "\"terminalCoreMessages\":%d,\"terminalCoreMessagesPerSec\":%.3f,"
                            + "\"sweepP50Micros\":%.3f,\"sweepP95Micros\":%.3f,"
                            + "\"sweepP99Micros\":%.3f,\"sweepMaxMicros\":%.3f,"
                            + "\"maxMatchingBacklog\":%d,\"incompleteRiskScans\":%d,"
                            + "\"incompleteFundingSettlements\":%d,\"initialHeapBytes\":%d,"
                            + "\"finalHeapBytes\":%d,\"maxHeapBytes\":%d,\"initialDirectBytes\":%d,"
                            + "\"finalDirectBytes\":%d,\"maxDirectBytes\":%d,"
                            + "\"postGcSamples\":%d,\"postGcLiveSetSlopeBytesPerSec\":%.3f,"
                            + "\"postGcOldGenerationSlopeBytesPerSec\":%.3f,"
                            + "\"postGcDirectSlopeBytesPerSec\":%.3f,"
                            + "\"postGcMappedSlopeBytesPerSec\":%.3f,"
                            + "\"postGcThreadSlopePerSec\":%.6f,\"postGcFdSlopePerSec\":%.6f,"
                            + "\"directPoolBalanceSlopePerSec\":%.6f,"
                            + "\"mappedPoolBalanceSlopePerSec\":%.6f,"
                            + "\"leakThresholds\":{\"liveSetBytesPerSec\":%d,"
                            + "\"nativeBufferBytesPerSec\":%d,\"threadsPerSec\":%.6f,"
                            + "\"fdsPerSec\":%.6f,\"poolBalancePerSec\":%.6f},"
                            + "\"snapshotBytes\":%d,\"restoreMillis\":%.3f}%n",
                    status, config.activeUsers(), config.listedSymbols(), config.activeSymbols(),
                    config.maxPositionsPerUser(), config.maxOpenOrdersPerUser(), config.trafficProfile(),
                    config.lifecycleSymbolsPerRun(), elapsedNanos / 1_000_000_000.0,
                    setupNanos / 1_000_000.0, terminalOperations,
                    terminalOperations / (elapsedNanos / 1_000_000_000.0), terminalCoreMessages,
                    terminalCoreMessages / (elapsedNanos / 1_000_000_000.0),
                    latencies.micros(0.50), latencies.micros(0.95), latencies.micros(0.99),
                    latencies.maxMicros(), maxBacklog, scenario.incompleteRiskScans(),
                    scenario.incompleteFundingSettlements(), initialHeap, finalHeap, maxHeap,
                    initialDirect, finalDirect, maxDirect, leak.samples(), leak.liveSetSlope(),
                    leak.oldGenerationSlope(), leak.directSlope(), leak.mappedSlope(), leak.threadSlope(), leak.fdSlope(),
                    leak.directPoolSlope(), leak.mappedPoolSlope(), leak.maxLiveSetSlope(),
                    leak.maxNativeBufferSlope(), leak.maxThreadSlope(), leak.maxFdSlope(),
                    leak.maxPoolBalanceSlope(), snapshot.sizeBytes(), restoreNanos / 1_000_000.0);
            if (!leak.pass()) throw new IllegalStateException("soak leak slope threshold failed: " + leak);
        }
    }

    private static long openFileDescriptors(java.lang.management.OperatingSystemMXBean operatingSystem) {
        if (operatingSystem instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            return unix.getOpenFileDescriptorCount();
        }
        return -1;
    }

    static LeakSlopeQualification exerciseLeakSlopeSmallScale() {
        LeakSamples samples = new LeakSamples();
        long[] values = {0, 100, 100_000, 300};
        for (int index = 0; index < values.length; index++) {
            long value = values[index];
            samples.add(index, index + 1L, 100 + index, value, value, value, value,
                    0, 0, 4, 8);
        }
        LeakVerdict verdict = samples.verdict();
        return new LeakSlopeQualification(verdict.pass(), verdict.samples(), verdict.liveSetSlope(),
                verdict.oldGenerationSlope(), verdict.directSlope(), verdict.mappedSlope());
    }

    static record LeakSlopeQualification(boolean pass, int samples, double liveSetSlope,
                                         double oldGenerationSlope, double directSlope,
                                         double mappedSlope) { }

    private static final class LeakSamples {
        private final long maxLiveSetSlope = Long.getLong(
                "surprising.benchmark.soak.max-live-set-slope-bytes-per-second", 1L << 20);
        private final long maxNativeBufferSlope = Long.getLong(
                "surprising.benchmark.soak.max-native-buffer-slope-bytes-per-second", 256L << 10);
        private final double maxThreadSlope = Double.parseDouble(System.getProperty(
                "surprising.benchmark.soak.max-thread-slope-per-second", "0.01"));
        private final double maxFdSlope = Double.parseDouble(System.getProperty(
                "surprising.benchmark.soak.max-fd-slope-per-second", "0.01"));
        private final double maxPoolBalanceSlope = Double.parseDouble(System.getProperty(
                "surprising.benchmark.soak.max-buffer-pool-balance-slope-per-second", "0.01"));
        private final int requiredSamples;
        private final ArrayList<Sample> samples = new ArrayList<>();

        private LeakSamples() {
            requiredSamples = 3;
        }

        private void add(double seconds, long signalSequence, long gcId, long liveSet, long oldGenerationBytes,
                         long directBytes, long mappedBytes,
                         long directCount, long mappedCount, int threadCount, long fileDescriptors) {
            if (!samples.isEmpty() && signalSequence <= samples.get(samples.size() - 1).signalSequence) {
                throw new IllegalStateException("post-GC signal sequence must be strictly increasing");
            }
            samples.add(new Sample(seconds, signalSequence, gcId, liveSet, oldGenerationBytes, directBytes,
                    mappedBytes, directCount, mappedCount, threadCount, fileDescriptors));
        }

        private LeakVerdict verdict() {
            if (samples.size() < requiredSamples) {
                return new LeakVerdict(false, samples.size(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                        Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                        Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, maxLiveSetSlope,
                        maxNativeBufferSlope, maxThreadSlope, maxFdSlope, maxPoolBalanceSlope);
            }
            double liveSetSlope = robustSlope(sample -> sample.liveSet);
            double oldGenerationSlope = robustSlope(sample -> sample.oldGenerationBytes);
            double directSlope = robustSlope(sample -> sample.directBytes);
            double mappedSlope = robustSlope(sample -> sample.mappedBytes);
            double threadSlope = robustSlope(sample -> sample.threadCount);
            double fdSlope = robustSlope(sample -> sample.fileDescriptors);
            double directPoolSlope = robustSlope(sample -> sample.directCount);
            double mappedPoolSlope = robustSlope(sample -> sample.mappedCount);
            boolean pass = liveSetSlope <= maxLiveSetSlope && oldGenerationSlope <= maxLiveSetSlope
                    && directSlope <= maxNativeBufferSlope
                    && mappedSlope <= maxNativeBufferSlope && threadSlope <= maxThreadSlope
                    && fdSlope <= maxFdSlope && directPoolSlope <= maxPoolBalanceSlope
                    && mappedPoolSlope <= maxPoolBalanceSlope;
            return new LeakVerdict(pass, samples.size(), liveSetSlope, oldGenerationSlope, directSlope, mappedSlope, threadSlope,
                    fdSlope, directPoolSlope, mappedPoolSlope, maxLiveSetSlope, maxNativeBufferSlope,
                    maxThreadSlope, maxFdSlope, maxPoolBalanceSlope);
        }

        private double robustSlope(ToDoubleFunction<Sample> value) {
            double[] slopes = new double[samples.size() * (samples.size() - 1) / 2];
            int count = 0;
            for (int left = 0; left < samples.size(); left++) {
                for (int right = left + 1; right < samples.size(); right++) {
                    Sample first = samples.get(left);
                    Sample last = samples.get(right);
                    double firstValue = value.applyAsDouble(first);
                    double lastValue = value.applyAsDouble(last);
                    double elapsed = last.seconds - first.seconds;
                    if (firstValue >= 0 && lastValue >= 0 && elapsed > 0) {
                        slopes[count++] = (lastValue - firstValue) / elapsed;
                    }
                }
            }
            if (count == 0) return 0;
            Arrays.sort(slopes, 0, count);
            int middle = count / 2;
            return count % 2 == 0 ? (slopes[middle - 1] + slopes[middle]) / 2.0 : slopes[middle];
        }

        private record Sample(double seconds, long signalSequence, long gcId, long liveSet, long oldGenerationBytes,
                              long directBytes, long mappedBytes,
                              long directCount, long mappedCount, long threadCount, long fileDescriptors) { }
    }

    private static final class GcCompletionTracker implements AutoCloseable {
        private final ConcurrentLinkedQueue<GcAfterSample> completed = new ConcurrentLinkedQueue<>();
        private final ArrayList<Registration> registrations = new ArrayList<>();
        private final AtomicLong signalSequence = new AtomicLong();
        private long completedCount;

        private GcCompletionTracker(List<GarbageCollectorMXBean> collectors) {
            NotificationListener listener = (notification, ignored) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(
                        notification.getType()) || !(notification.getUserData() instanceof CompositeData data)) {
                    return;
                }
                GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(data);
                Map<String, MemoryUsage> after = info.getGcInfo().getMemoryUsageAfterGc();
                long heapUsed = sumUsed(after, false);
                long oldUsed = sumUsed(after, true);
                completed.add(new GcAfterSample(signalSequence.incrementAndGet(), info.getGcInfo().getId(),
                        System.nanoTime(), heapUsed, oldUsed < 0 ? heapUsed : oldUsed));
            };
            for (GarbageCollectorMXBean collector : collectors) {
                if (collector instanceof NotificationEmitter emitter) {
                    emitter.addNotificationListener(listener, null, null);
                    registrations.add(new Registration(emitter, listener));
                }
            }
            if (registrations.isEmpty()) {
                throw new IllegalStateException("no GC notification emitter is available for post-GC leak sampling");
            }
        }

        private List<GcAfterSample> drain() {
            ArrayList<GcAfterSample> drained = new ArrayList<>();
            GcAfterSample sample;
            while ((sample = completed.poll()) != null) drained.add(sample);
            drained.sort(java.util.Comparator.comparingLong(GcAfterSample::signalSequence));
            completedCount = Math.addExact(completedCount, drained.size());
            return List.copyOf(drained);
        }

        private long completedCount() { return completedCount + completed.size(); }

        @Override
        public void close() {
            for (Registration registration : registrations) {
                try {
                    registration.emitter.removeNotificationListener(registration.listener);
                } catch (javax.management.ListenerNotFoundException ignored) {
                }
            }
        }

        private static long sumUsed(Map<String, MemoryUsage> pools, boolean oldOnly) {
            long used = 0;
            boolean found = false;
            for (Map.Entry<String, MemoryUsage> entry : pools.entrySet()) {
                String name = entry.getKey().toLowerCase(Locale.ROOT);
                boolean old = name.contains("old") || name.contains("zheap");
                if (!oldOnly || old) {
                    used = Math.addExact(used, entry.getValue().getUsed());
                    found = true;
                }
            }
            return found ? used : -1;
        }

        private record Registration(NotificationEmitter emitter, NotificationListener listener) { }
    }

    private record GcAfterSample(long signalSequence, long gcId, long completedAtNanos,
                                 long heapUsedAfterGc, long oldGenerationUsedAfterGc) { }

    private record LeakVerdict(boolean pass, int samples, double liveSetSlope,
                               double oldGenerationSlope, double directSlope, double mappedSlope,
                               double threadSlope, double fdSlope,
                               double directPoolSlope, double mappedPoolSlope, long maxLiveSetSlope,
                               long maxNativeBufferSlope, double maxThreadSlope, double maxFdSlope,
                               double maxPoolBalanceSlope) { }

    private record SoakConfig(int activeUsers, int listedSymbols, int activeSymbols,
                              int maxPositionsPerUser, int maxOpenOrdersPerUser,
                              LinearPerpetualTrafficProfile trafficProfile, int hftRounds,
                              int hftBatchSize, int lifecycleSymbolsPerRun,
                              int durationSeconds, int sampleSeconds) {
        private static SoakConfig parse(String[] args) {
            if (args.length != 11) {
                throw new IllegalArgumentException("usage: users listed active maxPositions maxOpenOrders "
                        + "profile hftRounds hftBatchSize lifecycleSymbolsPerRun durationSeconds sampleSeconds");
            }
            return new SoakConfig(positive(args[0], "activeUsers"), positive(args[1], "listedSymbols"),
                    positive(args[2], "activeSymbols"), positive(args[3], "maxPositionsPerUser"),
                    nonNegative(args[4], "maxOpenOrdersPerUser"),
                    LinearPerpetualTrafficProfile.parse(args[5]), positive(args[6], "hftRounds"),
                    positive(args[7], "hftBatchSize"), positive(args[8], "lifecycleSymbolsPerRun"),
                    positive(args[9], "durationSeconds"), positive(args[10], "sampleSeconds"));
        }

        private static int positive(String value, String name) {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw new IllegalArgumentException(name + " must be positive");
            return parsed;
        }

        private static int nonNegative(String value, String name) {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new IllegalArgumentException(name + " must be non-negative");
            return parsed;
        }
    }

    private static final class PrimitiveLatencies {
        private long[] values = new long[256];
        private int size;
        private boolean sorted;

        private void add(long value) {
            if (size == values.length) values = Arrays.copyOf(values, Math.multiplyExact(values.length, 2));
            values[size++] = value;
            sorted = false;
        }

        private double micros(double percentile) {
            sort();
            int index = Math.max(0, (int) Math.ceil(percentile * size) - 1);
            return values[index] / 1_000.0;
        }

        private double maxMicros() {
            sort();
            return values[size - 1] / 1_000.0;
        }

        private void sort() {
            if (size == 0) throw new IllegalStateException("soak produced no measured sweep");
            if (sorted) return;
            Arrays.sort(values, 0, size);
            sorted = true;
        }
    }
}
