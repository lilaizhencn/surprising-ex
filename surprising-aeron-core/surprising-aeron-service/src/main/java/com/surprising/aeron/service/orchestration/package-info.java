/**
 * Owner-side orchestration: cluster lifecycle, matching admission, ordered commit coordination,
 * snapshot fences and owner scheduling. Business command handlers live under
 * {@code service.command.<business>} and receive narrow owner contexts; matcher workers and
 * account-lane mutation belong to their own packages.
 */
package com.surprising.aeron.service.orchestration;
