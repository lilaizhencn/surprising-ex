package com.surprising.trading.trigger.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 触发模块序列仓储，只负责 PostgreSQL 原生序列，不访问业务表。
 */
@Repository
public class TriggerSequenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public TriggerSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextSequence(String sequenceName) {
        Number value = jdbcTemplate.queryForObject("SELECT nextval(CAST(? AS regclass))", Number.class,
                tradingSequenceIdentifier(sequenceName));
        if (value == null || value.longValue() <= 0) {
            throw new IllegalStateException("分配触发模块序列失败：" + sequenceName);
        }
        return value.longValue();
    }

    private String tradingSequenceIdentifier(String sequenceName) {
        if (sequenceName == null || !sequenceName.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("非法的交易序列名称：" + sequenceName);
        }
        return "public.trading_" + sequenceName.toLowerCase().replace('-', '_') + "_seq";
    }
}
