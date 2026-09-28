package com.surprising.instrument.provider.repository;

import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only operational evidence, never a source of executable historical configurations. */
@Repository
public class InstrumentChangeLogRepository {
    private final JdbcTemplate jdbc;
    public InstrumentChangeLogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long nextId() {
        return jdbc.queryForObject("SELECT nextval('instrument_change_log_sequence')",Long.class);
    }

    public void append(ProductLine line,int instrumentId,String symbol,long id,String operator,String reason,Instant now,
                       String before,String after) {
        if(operator==null || operator.isBlank() || reason==null || reason.isBlank()) {
            throw new IllegalArgumentException("instrument change operator and reason are required");
        }
        jdbc.update("""
                INSERT INTO instrument_change_log(product_line,instrument_id,symbol,change_id,operator_id,reason,changed_at,before_values,after_values)
                VALUES(?,?,?,?,?,?, ?,?::jsonb,?::jsonb)
                """,line.name(),instrumentId,symbol,id,operator,reason,java.sql.Timestamp.from(now),before,after);
    }

    public com.surprising.instrument.api.model.InstrumentTradeEncoding tradeEncoding(ProductLine line,int instrumentId,long id) {
        if (line==null || instrumentId<=0 || id<=0) throw new IllegalArgumentException("invalid trade audit reference");
        // The immutable audit row supplies units only, never fees, risk policy or trading status.
        return jdbc.queryForObject("""
                SELECT (l.after_values->>'priceTickUnits')::bigint, (l.after_values->>'quantityStepUnits')::bigint,
                       (l.after_values->>'baseScaleUnits')::bigint, (l.after_values->>'quoteScaleUnits')::bigint
                FROM instrument_change_log l
                WHERE l.product_line=? AND l.instrument_id=? AND l.change_id=?
                """,(r,n)->new com.surprising.instrument.api.model.InstrumentTradeEncoding(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4)),
                line.name(),instrumentId,id);
    }

    public record Entry(String changeId,String operatorId,String reason,String changedAt,String beforeValues,String afterValues) { }
    public List<Entry> list(ProductLine line,int instrumentId,long beforeId,int limit) {
        if(line==null || instrumentId<=0 || beforeId<0 || limit<1 || limit>100) throw new IllegalArgumentException("invalid audit query");
        return jdbc.query("""
                SELECT change_id,operator_id,reason,changed_at,before_values::text,after_values::text
                FROM instrument_change_log WHERE product_line=? AND instrument_id=? AND (?=0 OR change_id<?)
                ORDER BY change_id DESC LIMIT ?
                """,(r,n)->new Entry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)),
                line.name(),instrumentId,beforeId,beforeId,limit);
    }
}
