package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.realtime.provider.export.model.CommandStatisticsBucket;

/** 仅访问core_trading_command_statistics；分钟桶累计、窗口计数及保留期清理。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommandStatisticsRepository {
    private final JdbcTemplate jdbc;

    public CommandStatisticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void add(ProductLine product, Map<CommandStatisticsBucket,Long> buckets) {
        jdbc.batchUpdate("""
                INSERT INTO core_trading_command_statistics VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (product_line,minute_epoch_ms,user_id,command_type,command_status,result_code)
                DO UPDATE SET commands=core_trading_command_statistics.commands+EXCLUDED.commands
                """,buckets.entrySet(),256,(s,e)->{
            var k=e.getKey();s.setString(1,product.name());s.setLong(2,k.minute());s.setLong(3,k.user());s.setString(4,k.type().name());
            s.setString(5,k.status().name());s.setString(6,k.code().name());s.setLong(7,e.getValue());
        });
    }
    public List<Map<String,Object>> breakdown(ProductLine product, long from) {
        return jdbc.queryForList("""
                SELECT command_type AS "commandType",command_status AS "status",result_code AS "resultCode",sum(commands) AS "commands"
                FROM core_trading_command_statistics WHERE product_line=? AND minute_epoch_ms>=?
                GROUP BY command_type,command_status,result_code ORDER BY command_type,command_status,result_code
                """,product.name(),from);
    }
    public long commandUsers(ProductLine product, long from) {
        return jdbc.queryForObject("SELECT count(DISTINCT user_id) FROM core_trading_command_statistics WHERE product_line=? AND minute_epoch_ms>=?",Long.class,product.name(),from);
    }
    public long orderUsers(ProductLine product, long from) {
        return jdbc.queryForObject("SELECT count(DISTINCT user_id) FROM core_trading_command_statistics WHERE product_line=? AND minute_epoch_ms>=? AND command_type IN ('PLACE_ORDER','PLACE_ORDER_BATCH')",Long.class,product.name(),from);
    }
    public void prune(ProductLine product, long before) {
        jdbc.update("DELETE FROM core_trading_command_statistics WHERE product_line=? AND minute_epoch_ms<?",product.name(),before);
    }

}
