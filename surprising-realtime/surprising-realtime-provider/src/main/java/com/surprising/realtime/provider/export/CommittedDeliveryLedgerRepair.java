package com.surprising.realtime.provider.export;

import com.surprising.product.api.ProductLine;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 从保留的已提交日志回补交割现金账本；不重发交易、订单回执或 Kafka 行情。 */
public final class CommittedDeliveryLedgerRepair {
    private CommittedDeliveryLedgerRepair() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException(
                "product cluster-directory aeron-directory archive-control-channel checkpoint.delivery-repair end-position");
        var source = new DriverManagerDataSource(required("SPRING_DATASOURCE_URL"),
                required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        var repository = new CommittedOrderProjectionRepository(new JdbcTemplate(source), new DataSourceTransactionManager(source));
        var product = ProductLine.valueOf(args[0]);
        var config = new TradeExportProperties(Path.of(args[1]), args[2], args[3], Path.of(args[4]), null, null, null, null);
        new CommittedTradeExporter(config, product, null, repository).repairDelivery(Long.parseLong(args[5]));
        System.out.println("DELIVERY_LEDGER_REPAIR_COMPLETE product=" + product + " end=" + args[5]);
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null) throw new IllegalArgumentException("missing deployment environment " + name);
        return value;
    }
}
