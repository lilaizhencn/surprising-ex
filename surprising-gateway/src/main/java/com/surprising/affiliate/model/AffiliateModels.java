package com.surprising.affiliate.model;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** Amounts and identities are decimal strings on HTTP boundaries, preserving int64 precision. */
public final class AffiliateModels {

  private AffiliateModels() {}

  public record Settings(
    long version,
    int referralRatePpm,
    int kolRatePpm,
    int settlementHours
  ) {}

  public record SettingsInput(
    @Min(0) @Max(1000000) int referralRatePpm,
    @Min(0) @Max(1000000) int kolRatePpm,
    @Min(1) @Max(720) int settlementHours,
    @NotBlank @Size(max = 1000) String reason
  ) {}

  public record Profile(
    String userId,
    String inviteCode,
    boolean kolEnabled,
    boolean autoSettlement,
    Integer ratePpm,
    Instant createdAt
  ) {}

  public record ProfileInput(
    boolean kolEnabled,
    boolean autoSettlement,
    @Min(0) @Max(1000000) Integer ratePpm,
    @NotBlank @Size(max = 1000) String reason
  ) {}

  public record AcceptInput(@NotBlank @Size(max = 64) String code) {}

  public record InviteInput(
    @Pattern(regexp = "[1-9][0-9]{0,18}") String targetUserId,
    @Min(0) @Max(1000000) int sharePpm
  ) {}

  public record ShareInput(@Min(0) @Max(1000000) int sharePpm) {}

  public record BudgetInput(
    @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String userId,
    @NotBlank @Size(max = 1000) String reason
  ) {}

  public record Invitation(
    String code,
    String inviterId,
    String targetUserId,
    boolean cooperation,
    Integer sharePpm,
    Instant expiresAt,
    String acceptedBy
  ) {}

  public record TeamTerm(
    long termId,
    long parentId,
    long childId,
    int sharePpm
  ) {}

  public record Node(long userId, boolean kolEnabled, Integer ratePpm) {}

  public record Allocation(
    long userId,
    long receivedUnits,
    long delegatedUnits,
    long retainedUnits,
    Long termId,
    int sharePpm
  ) {}

  public record Fee(
    long clusterPosition,
    String productLine,
    long userId,
    long orderId,
    String asset,
    long feeUnits,
    Instant occurredAt
  ) {}

  public record SourceCursor(long clusterPosition, long orderId) {}

  public record Page<T>(List<T> items, String nextCursor) {}
}
