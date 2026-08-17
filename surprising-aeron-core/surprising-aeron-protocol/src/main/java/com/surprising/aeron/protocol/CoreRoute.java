package com.surprising.aeron.protocol;

public enum CoreRoute {
    DEFAULT(0, 1);

    private final int shardCode;
    private final int version;

    CoreRoute(int shardCode, int version) {
        this.shardCode = shardCode;
        this.version = version;
    }

    public int shardCode() {
        return shardCode;
    }

    public int version() {
        return version;
    }

    public static CoreRoute fromWireCodes(int shardCode, int version) {
        if (shardCode != DEFAULT.shardCode) {
            throw new ProtocolException("unsupported core shard: " + shardCode);
        }
        if (version != DEFAULT.version) {
            throw new ProtocolException("unsupported core route version: " + version);
        }
        return DEFAULT;
    }
}
