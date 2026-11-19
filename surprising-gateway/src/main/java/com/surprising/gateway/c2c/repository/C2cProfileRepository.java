package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cProfileRows.Profile;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_profiles，验证设置的共享锁由调用方事务持有。 */
@Repository
public class C2cProfileRepository {
    private final JdbcTemplate jdbc;

    public C2cProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Integer> verificationMinutesWithShareLock(long userId) {
        return jdbc.query("SELECT verification_minutes FROM c2c_profiles WHERE user_id=? FOR SHARE",
                (r, n) -> r.getInt(1), userId).stream().findFirst();
    }
    public Optional<Profile> find(long userId,boolean lock) {
        return jdbc.query("SELECT * FROM c2c_profiles WHERE user_id=?"+(lock?" FOR UPDATE":""),
                (r,n)->new Profile(r.getString("nickname"),r.getBoolean("receive_strangers"),
                        r.getInt("verification_minutes"),r.getLong("revision"),
                        r.getInt("timeout_strikes"),r.getBoolean("blacklisted"),r.getString("blacklist_reason"),
                        r.getString("unblock_appeal_status"),r.getString("unblock_appeal_reason"),
                        r.getString("unblock_review_note")),userId).stream().findFirst();
    }
    public boolean isBlacklisted(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COALESCE((SELECT blacklisted FROM c2c_profiles WHERE user_id=?), false)", Boolean.class, userId));
    }
    public int recordTimeoutStrike(long userId) {
        String defaultNickname = "用户" + String.valueOf(userId);
        jdbc.update("""
                INSERT INTO c2c_profiles(user_id, nickname, timeout_strikes, blacklisted, blacklist_reason)
                VALUES(?, ?, 1, false, NULL)
                ON CONFLICT (user_id) DO UPDATE SET
                    timeout_strikes = c2c_profiles.timeout_strikes + 1,
                    blacklisted = CASE WHEN c2c_profiles.timeout_strikes + 1 >= 2 THEN true ELSE c2c_profiles.blacklisted END,
                    blacklist_reason = CASE WHEN c2c_profiles.timeout_strikes + 1 >= 2
                        THEN '累计超过一次 C2C 订单超时未付款或未确认，已被系统限制下单'
                        ELSE c2c_profiles.blacklist_reason END,
                    updated_at = now()
                """, userId, defaultNickname);
        Integer strikes = jdbc.queryForObject("SELECT timeout_strikes FROM c2c_profiles WHERE user_id=?", Integer.class, userId);
        return strikes == null ? 1 : strikes;
    }
    public void submitUnblockAppeal(long userId, String reason) {
        jdbc.update("""
                UPDATE c2c_profiles SET unblock_appeal_status='PENDING', unblock_appeal_reason=?,
                    unblock_review_note=NULL, updated_at=now() WHERE user_id=?
                """, reason, userId);
    }
    public void reviewUnblockAppeal(long userId, boolean approve, String reviewNote) {
        if (approve) {
            jdbc.update("""
                    UPDATE c2c_profiles SET blacklisted=false, timeout_strikes=0, blacklist_reason=NULL,
                        unblock_appeal_status='APPROVED', unblock_review_note=?, updated_at=now() WHERE user_id=?
                    """, reviewNote, userId);
        } else {
            jdbc.update("""
                    UPDATE c2c_profiles SET unblock_appeal_status='REJECTED', unblock_review_note=?, updated_at=now()
                    WHERE user_id=?
                    """, reviewNote, userId);
        }
    }
    public java.util.List<java.util.Map<String,Object>> adminBlacklistAppeals(String status, int limit, int offset) {
        return jdbc.query("""
                SELECT user_id, nickname, timeout_strikes, blacklisted, blacklist_reason,
                    unblock_appeal_status, unblock_appeal_reason, unblock_review_note, updated_at
                FROM c2c_profiles
                WHERE (blacklisted = true OR unblock_appeal_status IS NOT NULL)
                    AND (CAST(? AS text) IS NULL OR unblock_appeal_status = ?)
                ORDER BY updated_at DESC, user_id DESC LIMIT ? OFFSET ?
                """, (r, n) -> {
                    var row = new java.util.LinkedHashMap<String,Object>();
                    row.put("userId", Long.toString(r.getLong("user_id")));
                    row.put("nickname", r.getString("nickname"));
                    row.put("timeoutStrikes", r.getInt("timeout_strikes"));
                    row.put("blacklisted", r.getBoolean("blacklisted"));
                    row.put("blacklistReason", r.getString("blacklist_reason"));
                    row.put("status", r.getString("unblock_appeal_status") == null ? "BLACKLISTED" : r.getString("unblock_appeal_status"));
                    row.put("appealReason", r.getString("unblock_appeal_reason"));
                    row.put("reviewNote", r.getString("unblock_review_note"));
                    row.put("updatedAt", r.getTimestamp("updated_at").toInstant().toString());
                    return row;
                }, status, status, limit, offset);
    }
    public void insert(long userId,String nickname,boolean strangers,int minutes) {
        jdbc.update("INSERT INTO c2c_profiles(user_id,nickname,receive_strangers,verification_minutes) VALUES(?,?,?,?)",
                userId,nickname,strangers,minutes);
    }
    public void update(long userId,String nickname,boolean strangers,int minutes) {
        jdbc.update("""
                UPDATE c2c_profiles SET nickname=?,receive_strangers=?,verification_minutes=?,revision=revision+1,updated_at=now()
                WHERE user_id=?
                """,nickname,strangers,minutes,userId);
    }

}
