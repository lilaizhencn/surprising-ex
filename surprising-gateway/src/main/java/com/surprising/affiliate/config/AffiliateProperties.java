package com.surprising.affiliate.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Operational bounds, never an instruction to truncate a member's financial path. */
@Component
@Validated
@ConfigurationProperties(prefix = "surprising.affiliate")
public class AffiliateProperties {

  @Min(2)
  @Max(16384)
  private int maxPathNodes = 4096;

  @Min(1)
  @Max(256)
  private int feeBatchSize = 64;

  @Min(1)
  @Max(65536)
  private int maxAllocationRows = 8192;

  @Min(25)
  @Max(2000)
  private int batchMillis = 250;

  @Min(1)
  @Max(15)
  private int queryTimeoutSeconds = 2;

  @Min(1)
  @Max(30)
  private int transactionTimeoutSeconds = 5;

  @Min(1)
  @Max(50)
  private int payoutGroups = 16;

  @Min(1)
  @Max(1000)
  private int payoutRows = 256;

  @Min(1)
  @Max(50)
  private int payoutAttempts = 10;

  @Min(30)
  @Max(600)
  private int claimSeconds = 120;

  @Min(1)
  @Max(8)
  private int concurrentReads = 4;

  @Min(1)
  @Max(100000)
  private int maxDeferredFees = 10000;

  @Min(1)
  @Max(64)
  private int deferredBatchSize = 16;

  @Min(1)
  @Max(3600)
  private int deferredRetrySeconds = 60;

  public int getMaxDeferredFees() {
    return maxDeferredFees;
  }

  public void setMaxDeferredFees(int v) {
    maxDeferredFees = v;
  }

  public int getDeferredBatchSize() {
    return deferredBatchSize;
  }

  public void setDeferredBatchSize(int v) {
    deferredBatchSize = v;
  }

  public int getDeferredRetrySeconds() {
    return deferredRetrySeconds;
  }

  public void setDeferredRetrySeconds(int v) {
    deferredRetrySeconds = v;
  }

  public int getConcurrentReads() {
    return concurrentReads;
  }

  public void setConcurrentReads(int v) {
    concurrentReads = v;
  }

  @AssertTrue(message = "allocation row budget must cover one complete path")
  public boolean isCompletePathBudget() {
    return maxAllocationRows >= maxPathNodes;
  }

  @AssertTrue(message = "transaction timeout must cover one query")
  public boolean isValidQueryDeadline() {
    return transactionTimeoutSeconds >= queryTimeoutSeconds;
  }

  public int getMaxPathNodes() {
    return maxPathNodes;
  }

  public void setMaxPathNodes(int v) {
    maxPathNodes = v;
  }

  public int getFeeBatchSize() {
    return feeBatchSize;
  }

  public void setFeeBatchSize(int v) {
    feeBatchSize = v;
  }

  public int getMaxAllocationRows() {
    return maxAllocationRows;
  }

  public void setMaxAllocationRows(int v) {
    maxAllocationRows = v;
  }

  public int getBatchMillis() {
    return batchMillis;
  }

  public void setBatchMillis(int v) {
    batchMillis = v;
  }

  public int getQueryTimeoutSeconds() {
    return queryTimeoutSeconds;
  }

  public void setQueryTimeoutSeconds(int v) {
    queryTimeoutSeconds = v;
  }

  public int getTransactionTimeoutSeconds() {
    return transactionTimeoutSeconds;
  }

  public void setTransactionTimeoutSeconds(int v) {
    transactionTimeoutSeconds = v;
  }

  public int getPayoutGroups() {
    return payoutGroups;
  }

  public void setPayoutGroups(int v) {
    payoutGroups = v;
  }

  public int getPayoutRows() {
    return payoutRows;
  }

  public void setPayoutRows(int v) {
    payoutRows = v;
  }

  public int getPayoutAttempts() {
    return payoutAttempts;
  }

  public void setPayoutAttempts(int v) {
    payoutAttempts = v;
  }

  public int getClaimSeconds() {
    return claimSeconds;
  }

  public void setClaimSeconds(int v) {
    claimSeconds = v;
  }
}
