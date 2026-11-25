package com.surprising.gateway.provider.auth.model;

public sealed interface LoginResult permits AuthResponse, LoginVerificationModels.ChallengeResponse {}
