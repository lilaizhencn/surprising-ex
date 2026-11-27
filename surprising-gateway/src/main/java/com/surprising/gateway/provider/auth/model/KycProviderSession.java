package com.surprising.gateway.provider.auth.model;

public record KycProviderSession(String provider, String providerReference, String launchToken,
                                 String redirectUrl, String status, boolean simulated) {}
