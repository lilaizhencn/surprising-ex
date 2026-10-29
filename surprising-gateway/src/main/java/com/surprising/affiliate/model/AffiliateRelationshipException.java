package com.surprising.affiliate.model;

/** Historical relationship cannot be resolved. Raised before any financial allocation is written. */
public final class AffiliateRelationshipException
  extends IllegalStateException
{

  public AffiliateRelationshipException(String message) {
    super(message);
  }
}
