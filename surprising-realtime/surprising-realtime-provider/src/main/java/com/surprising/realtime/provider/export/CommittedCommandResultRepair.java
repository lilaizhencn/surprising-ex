package com.surprising.realtime.provider.export;

import com.surprising.product.api.ProductLine;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 只回补已提交回执；独立检查点，不写订单水位、不重发 Kafka 或交易命令。 */
public final class CommittedCommandResultRepair {
    private CommittedCommandResultRepair() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException(
                "product cluster-directory aeron-directory archive-control-channel checkpoint.command-repair end-position");
        var source = new DriverManagerDataSource(required("SPRING_DATASOURCE_URL"),
                required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"));
        var repository = new CommittedOrderProjectionRepository(new JdbcTemplate(source), new DataSourceTransactionManager(source));
        var product = ProductLine.valueOf(args[0]);
        var config = new TradeExportProperties(Path.of(args[1]), args[2], args[3], Path.of(args[4]), null, null, null, null);
        new CommittedTradeExporter(config, product, null, repository).repairCommandResults(Long.parseLong(args[5]));
        System.out.println("COMMAND_RESULT_REPAIR_COMPLETE product=" + product + " end=" + args[5]);
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null) throw new IllegalArgumentException("missing deployment environment " + name);
        return value;
    }
}
