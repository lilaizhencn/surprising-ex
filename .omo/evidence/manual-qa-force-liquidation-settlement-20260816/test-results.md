# Targeted test and benchmark evidence

All Maven tests used IBM Semeru JDK 25.0.2.1 explicitly:
`env JAVA_HOME=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin`

## Targeted Maven tests

1. Surface: liquidation provider work/configuration
   Invocation: `mvn -pl surprising-liquidation -am -Dtest=LiquidationServiceTest,LiquidationPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`
   Result: exit 0; `LiquidationPropertiesTest` 1/1 and `LiquidationServiceTest` 3/3; total 4/4; failures 0, errors 0, skipped 0; reactor `BUILD SUCCESS`.

2. Surface: account settlement fanout/consumer/math
   Invocation: `mvn -pl surprising-account/surprising-account-provider -am -Dtest=ExpiringContractSettlementFanoutServiceTest,ExpiringContractSettlementConsumerTest,PnlSettlementMathTest -Dsurefire.failIfNoSpecifiedTests=false test`
   Result: exit 0; fanout 3/3, PnL math 6/6, consumer 6/6; total 15/15; failures 0, errors 0, skipped 0; reactor `BUILD SUCCESS`.
   Expected negative cases logged explicit topic/key mismatch exceptions and still passed their assertions.

3. Surface: in-memory Core risk, liquidation, settlement, and matching state
   Invocation: `mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreProbeStateTest,CoreLifecycleStateTest,CoreRiskStateTest,CoreMatchingStateTest,CoreContractMathTest,TradingCoreReducerTest -Dsurefire.failIfNoSpecifiedTests=false test`
   Result: exit 0; CoreProbeState 23/23, CoreLifecycleState 12/12, CoreRiskState 7/7, TradingCoreReducer 24/24, CoreContractMath 1/1, CoreMatchingState 13/13; total 80/80; failures 0, errors 0, skipped 0; reactor `BUILD SUCCESS`.

4. Surface: Aeron client pool configuration/backpressure/close behavior
   Invocation: `mvn -pl surprising-aeron-core/surprising-aeron-client -am -Dtest=AeronClientPoolTest -Dsurefire.failIfNoSpecifiedTests=false test`
   Result: exit 0; 8/8; failures 0, errors 0, skipped 0; reactor `BUILD SUCCESS`.

5. Surface: Aeron command/query codecs and exported liquidation state
   Invocation: `mvn -pl surprising-aeron-core/surprising-aeron-protocol -am -Dtest=TradingCommandCodecTest,CoreExportCodecTest -Dsurefire.failIfNoSpecifiedTests=false test`
   Result: exit 0; TradingCommandCodec 4/4, CoreExportCodec 2/2; total 6/6; failures 0, errors 0, skipped 0; reactor `BUILD SUCCESS`.

6. Surface: Core exporter/projector liquidation/funding settlement projection
   Invocation: `mvn -pl surprising-aeron-core/surprising-aeron-exporter -am -Dtest=JdbcCoreEventProjectorTest,ReliableCoreExporterTest -Dsurefire.failIfNoSpecifiedTests=false test`
   Result: exit 0; JdbcCoreEventProjector 4/4, ReliableCoreExporter 3/3; total 7/7; failures 0, errors 0, skipped 0; reactor `BUILD SUCCESS`.

7. Surface: risk liquidation candidate/runtime controls
   Invocation: `mvn -pl surprising-risk/surprising-risk-provider -am -Dtest=RiskRuntimeConfigServiceTest,RiskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
   Result: exit 0; 8/8; failures 0, errors 0, skipped 0; reactor `BUILD SUCCESS`.

8. Surface: ADL continuation/provider math
   Invocation: `mvn -pl surprising-adl -am -Dtest=AdlServiceTest,AdlMathTest -Dsurefire.failIfNoSpecifiedTests=false test`
   Result: exit 0; AdlService 3/3, AdlMath 3/3; total 6/6; failures 0, errors 0, skipped 0; reactor `BUILD SUCCESS`.

## Existing benchmark

1. Surface: in-memory Core reducer/matching benchmark; no network, Kafka, wallet, or Aeron cluster.
   First invocation: `mvn -q -pl surprising-aeron-core/surprising-aeron-service -am org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.surprising.aeron.service.CoreInMemoryBenchmark -Dexec.args='5000 500'`
   Result: exit 1 because the exec goal resolved at `surprising-parent` and could not load the class. No benchmark result was produced.
   Successful invocation: `mvn -q -f surprising-aeron-core/surprising-aeron-service/pom.xml org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.surprising.aeron.service.CoreInMemoryBenchmark -Dexec.args='5000 500'`
   Result: exit 0; `inMemoryCoreBenchmark=PASS orders=5000 elapsedSeconds=4.532 ordersPerSec=1103.350 p50Micros=748 p95Micros=1245 p99Micros=3631 maxMicros=34311`.

## Warnings

Maven runs emitted existing JDK 25 Mockito/Byte Buddy dynamic-agent warnings and Aeron/Chronicle restricted-access warnings. No test failed because of them. No product source was edited and no wallet process was started.
