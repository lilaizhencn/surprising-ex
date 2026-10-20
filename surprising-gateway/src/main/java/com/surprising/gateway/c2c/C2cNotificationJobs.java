package com.surprising.gateway.c2c;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 仅 SQL 的小批量通知与清理；不调用 Core，也不阻塞资金续办线程。 */
@Component
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cNotificationJobs {
    private final JdbcTemplate jdbc;
    public C2cNotificationJobs(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @Scheduled(fixedDelayString="${surprising.c2c.notification-delay-ms:5000}",scheduler="taskScheduler")
    @Transactional
    public void tick() {
        notifyPriceAlerts();
        jdbc.update("""
                DELETE FROM c2c_security_grants WHERE (user_id,token_hash) IN (
                    SELECT user_id,token_hash FROM c2c_security_grants WHERE expires_at<now()
                    ORDER BY expires_at LIMIT 100 FOR UPDATE SKIP LOCKED)
                """);
        jdbc.update("""
                DELETE FROM c2c_quotes WHERE quote_id IN (
                    SELECT q.quote_id FROM c2c_quotes q WHERE q.expires_at<now()-interval '1 day'
                        AND NOT EXISTS(SELECT 1 FROM c2c_orders o WHERE o.quote_id=q.quote_id)
                    ORDER BY q.expires_at LIMIT 100 FOR UPDATE OF q SKIP LOCKED)
                """);
    }

    int notifyPriceAlerts() {
        // 每条预警的关闭与通知插入共同提交；并行 Gateway 不能重复通知同一次触发。
        return jdbc.update("""
                WITH ready AS (
                    SELECT p.alert_id FROM c2c_price_alerts p
                    JOIN c2c_configuration cfg ON cfg.singleton AND cfg.enabled
                    JOIN c2c_markets m ON m.market_id=p.market_id AND m.enabled
                    JOIN assets x ON x.asset=m.asset AND x.listed AND x.trading_enabled
                    WHERE p.enabled AND EXISTS (
                        SELECT 1 FROM c2c_ads a
                        JOIN c2c_merchants c ON c.user_id=a.maker_user_id AND c.status='APPROVED'
                        JOIN gateway_users u ON u.user_id=a.maker_user_id AND u.status='NORMAL'
                        LEFT JOIN gateway_user_kyc_profiles k ON k.user_id=a.maker_user_id
                        WHERE a.market_id=p.market_id AND a.status='ACTIVE' AND a.available_units>0 AND a.maker_user_id<>p.user_id
                            AND ROUND(a.available_units::numeric*a.price/x.scale_units,m.fiat_decimals)>=a.min_fiat
                            AND (NOT m.require_kyc OR (k.status='VERIFIED' AND (k.expires_at IS NULL OR k.expires_at>now())))
                            AND ((p.side='BUY' AND a.side='SELL' AND a.price<=p.target_price)
                                OR (p.side='SELL' AND a.side='BUY' AND a.price>=p.target_price))
                            AND EXISTS(SELECT 1 FROM c2c_payment_methods pm WHERE pm.enabled AND pm.code=ANY(a.method_codes) AND m.fiat=ANY(pm.currencies))
                            AND NOT EXISTS(SELECT 1 FROM c2c_relations r WHERE r.kind='BLOCK' AND
                                ((r.user_id=p.user_id AND r.target_user_id=a.maker_user_id) OR (r.target_user_id=p.user_id AND r.user_id=a.maker_user_id)))
                    ) ORDER BY p.created_at,p.alert_id LIMIT 32 FOR UPDATE OF p SKIP LOCKED
                ), fired AS (
                    UPDATE c2c_price_alerts p SET enabled=false,triggered_at=now(),revision=revision+1
                    FROM ready WHERE p.alert_id=ready.alert_id AND p.enabled RETURNING p.*
                )
                INSERT INTO c2c_notifications(user_id,kind,entity_id,details)
                SELECT f.user_id,'PRICE_ALERT',f.alert_id,jsonb_build_object('marketId',f.market_id::text,'asset',m.asset,'fiat',m.fiat,
                    'side',f.side,'targetPrice',f.target_price::text)
                FROM fired f JOIN c2c_markets m ON m.market_id=f.market_id
                """);
    }
}
