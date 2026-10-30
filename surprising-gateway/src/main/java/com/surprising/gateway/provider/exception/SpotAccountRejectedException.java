package com.surprising.gateway.provider.exception;

/** A failed Core decision is distinct from local validation or an unknown transport outcome. */
public class SpotAccountRejectedException extends IllegalStateException {
    private final String coreCode;
    public SpotAccountRejectedException(String message) { this(null,message); }
    public SpotAccountRejectedException(String coreCode,String message) { super(message); this.coreCode=coreCode; }
    public boolean isCoreDecision() { return coreCode!=null && !"IDEMPOTENCY_CONFLICT".equals(coreCode); }
}
