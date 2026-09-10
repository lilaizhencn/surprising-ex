package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Objects;

public final class ProductLineClusterLayout {

    private static final int BASE_PORT = 20_000;
    private static final int PORTS_PER_PRODUCT_LINE = 1_000;
    private static final int PORTS_PER_NODE = 100;
    public static final int MEMBER_COUNT = 3;
    public static final int ARCHIVE_CONTROL_OFFSET = 1;
    public static final int CLIENT_FACING_OFFSET = 2;
    public static final int MEMBER_FACING_OFFSET = 3;
    public static final int LOG_OFFSET = 4;
    public static final int TRANSFER_OFFSET = 5;

    private ProductLineClusterLayout() {
    }

    public static int clusterId(ProductLine productLine) {
        return 100 + Objects.requireNonNull(productLine, "productLine").ordinal();
    }

    public static int port(ProductLine productLine, int memberId, int offset) {
        if (memberId < 0 || memberId >= MEMBER_COUNT) {
            throw new IllegalArgumentException("memberId must be between 0 and 2");
        }
        return BASE_PORT + productLine.ordinal() * PORTS_PER_PRODUCT_LINE + memberId * PORTS_PER_NODE + offset;
    }

    public static String clusterMembers(ProductLine productLine, List<String> hostnames) {
        requireHostnames(hostnames);
        StringBuilder members = new StringBuilder();
        for (int memberId = 0; memberId < hostnames.size(); memberId++) {
            String host = hostnames.get(memberId);
            members.append(memberId)
                    .append(',').append(host).append(':').append(port(productLine, memberId, CLIENT_FACING_OFFSET))
                    .append(',').append(host).append(':').append(port(productLine, memberId, MEMBER_FACING_OFFSET))
                    .append(',').append(host).append(':').append(port(productLine, memberId, LOG_OFFSET))
                    .append(',').append(host).append(':').append(port(productLine, memberId, TRANSFER_OFFSET))
                    .append(',').append(host).append(':').append(port(productLine, memberId, ARCHIVE_CONTROL_OFFSET))
                    .append('|');
        }
        return members.toString();
    }

    public static String ingressEndpoints(ProductLine productLine, List<String> hostnames) {
        requireHostnames(hostnames);
        StringBuilder endpoints = new StringBuilder();
        for (int memberId = 0; memberId < hostnames.size(); memberId++) {
            if (!endpoints.isEmpty()) {
                endpoints.append(',');
            }
            endpoints.append(memberId).append('=').append(hostnames.get(memberId)).append(':')
                    .append(port(productLine, memberId, CLIENT_FACING_OFFSET));
        }
        return endpoints.toString();
    }

    /** 显式单成员部署用于本地开发；三成员部署保留原有地址与端口规则。 */
    public static void requireHostnames(List<String> hostnames) {
        if (hostnames == null || (hostnames.size() != 1 && hostnames.size() != MEMBER_COUNT)
                || hostnames.stream().anyMatch(host -> host == null || host.isBlank())) {
            throw new IllegalArgumentException("one or three non-blank hostnames are required");
        }
    }
}
