package com.surprising.gateway.provider.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 紧急提现风控熔断器
 * 一旦对账发现总账不平、负余额或异常流水，立即熔断停机全平台提现功能，支持内存 + Redis 分布式双写同步。
 */
@Component
public class EmergencyWithdrawalCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(EmergencyWithdrawalCircuitBreaker.class);
    private static final String REDIS_CIRCUIT_BREAKER_KEY = "system:withdraw:emergency_stopped";
    private static final String REDIS_STOP_REASON_KEY = "system:withdraw:stop_reason";

    private final StringRedisTemplate redis;
    private final AtomicBoolean emergencyStopped = new AtomicBoolean(false);
    private volatile String stopReason = "";
    private volatile Instant stoppedAt = null;

    public EmergencyWithdrawalCircuitBreaker(StringRedisTemplate redis) {
        this.redis = redis;
        // 初始化时从 Redis 恢复状态
        syncFromRedisQuietly();
    }

    /**
     * 是否允许执行提现（只有未被熔断停机时才允许）
     */
    public boolean isWithdrawalAllowed() {
        if (emergencyStopped.get()) {
            return false;
        }
        // 双重校验 Redis 分布式开关
        try {
            if (redis != null) {
                String val = redis.opsForValue().get(REDIS_CIRCUIT_BREAKER_KEY);
                if ("true".equalsIgnoreCase(val)) {
                    emergencyStopped.set(true);
                    return false;
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to check Redis emergency stop key, relying on memory state: {}", ex.getMessage());
        }
        return true;
    }

    /**
     * 触发紧急停机提现（发现对账不平时自动调用）
     */
    public void triggerEmergencyStop(String reason) {
        this.emergencyStopped.set(true);
        this.stopReason = (reason != null && !reason.isBlank()) ? reason : "资金对账异常熔断";
        this.stoppedAt = Instant.now();

        log.error("🚨🚨🚨 [EMERGENCY STOP] 平台提现已被紧急熔断停机！原因: {} 触发时间: {}", this.stopReason, this.stoppedAt);

        // 同步至 Redis
        try {
            if (redis != null) {
                redis.opsForValue().set(REDIS_CIRCUIT_BREAKER_KEY, "true");
                redis.opsForValue().set(REDIS_STOP_REASON_KEY, this.stopReason);
            }
        } catch (Exception ex) {
            log.error("Failed to write emergency stop flag to Redis: {}", ex.getMessage());
        }
    }

    /**
     * 管理员人工核验账目无误后，手动复位恢复提现
     */
    public void resumeWithdrawal(String authorizedBy) {
        this.emergencyStopped.set(false);
        this.stopReason = "";
        this.stoppedAt = null;

        log.info("✅ [EMERGENCY RESUME] 平台提现通道已由 [{}] 确认并恢复正常！", authorizedBy);

        try {
            if (redis != null) {
                redis.opsForValue().set(REDIS_CIRCUIT_BREAKER_KEY, "false");
                redis.delete(REDIS_STOP_REASON_KEY);
            }
        } catch (Exception ex) {
            log.error("Failed to clear emergency stop flag in Redis: {}", ex.getMessage());
        }
    }

    public boolean isStopped() {
        return !isWithdrawalAllowed();
    }

    public String getStopReason() {
        return stopReason;
    }

    public Instant getStoppedAt() {
        return stoppedAt;
    }

    private void syncFromRedisQuietly() {
        try {
            if (redis != null) {
                String val = redis.opsForValue().get(REDIS_CIRCUIT_BREAKER_KEY);
                if ("true".equalsIgnoreCase(val)) {
                    this.emergencyStopped.set(true);
                    this.stopReason = redis.opsForValue().get(REDIS_STOP_REASON_KEY);
                    this.stoppedAt = Instant.now();
                }
            }
        } catch (Exception ex) {
            log.debug("Redis not available during circuit breaker startup: {}", ex.getMessage());
        }
    }
}
