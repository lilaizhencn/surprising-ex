package com.surprising.gateway.c2c.utils;

import com.surprising.gateway.provider.support.Sha256Digests;
import java.nio.charset.StandardCharsets;

public final class C2cDigests {
    private C2cDigests() { }

    public static String sha256(String value) { return Sha256Digests.hex(value); }

    public static String sha256(byte[] value) { return Sha256Digests.hex(value); }
}
