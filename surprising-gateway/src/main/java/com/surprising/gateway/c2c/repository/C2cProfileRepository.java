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
                        r.getInt("verification_minutes"),r.getLong("revision")),userId).stream().findFirst();
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
