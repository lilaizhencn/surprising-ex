/**
 * Owner-side orchestration: {@code AeronTradingClusterService} adapts Aeron callbacks,
 * {@code TradingOwnerLoop} owns the trading thread, and {@code ClusterServiceEgress} owns
 * session state and response handoff; {@code TradingCoreOwner} owns replicated
 * command admission, matching progress, ordered commit, snapshot fences and realtime reads;
 * {@code TradingCoreQueryRouter} owns read-only query protocol routing.
 * {@code DirectCommandSlot} owns the single asynchronous control-command continuation;
 * {@code CommandSlot} owns only in-flight matching command state.
 * {@code SurprisingClusteredService} is a compatibility callback adapter only. Business command handlers live under
 * {@code service.command.<business>} and receive narrow owner contexts; matcher workers and
 * account-lane mutation belong to their own packages.
 * Protocol ingress decoding, cluster-thread idle scheduling, operational metrics, and public
 * snapshot metadata are separated into {@code orchestration.ingress},
 * {@code orchestration.cluster}, {@code orchestration.metrics}, and
 * {@code orchestration.snapshot}; classes that directly share Owner/Runtime state remain here.
 */
package com.surprising.aeron.service.orchestration;
