/**
 * Owner-side orchestration: {@code ContinuousTradingClusterService} owns Aeron callbacks,
 * sessions, queues and the Owner thread lifecycle; {@code TradingCoreOwner} owns replicated
 * command admission, matching progress, ordered commit, snapshot fences and realtime reads.
 * {@code SurprisingClusteredService} is a compatibility callback adapter only. Business command handlers live under
 * {@code service.command.<business>} and receive narrow owner contexts; matcher workers and
 * account-lane mutation belong to their own packages.
 */
package com.surprising.aeron.service.orchestration;
