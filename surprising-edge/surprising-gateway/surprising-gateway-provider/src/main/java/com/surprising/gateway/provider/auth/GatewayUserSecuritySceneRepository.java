package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GatewayUserSecuritySceneRepository {

    private final JdbcTemplate jdbcTemplate;

    public GatewayUserSecuritySceneRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SceneRecord> find(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, scene_code, enabled, created_at, updated_at
                  FROM gateway_user_security_scenes
                 WHERE user_id = ?
                 ORDER BY scene_code
                """, (rs, rowNum) -> new SceneRecord(
                rs.getLong("user_id"),
                rs.getString("scene_code"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), userId);
    }

    public Optional<SceneRecord> findOne(long userId, String sceneCode) {
        return find(userId).stream().filter(scene -> scene.sceneCode().equals(sceneCode)).findFirst();
    }

    public SceneRecord upsert(long userId, String sceneCode, boolean enabled, Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_user_security_scenes (
                    user_id, scene_code, enabled, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, scene_code) DO UPDATE
                   SET enabled = EXCLUDED.enabled,
                       updated_at = EXCLUDED.updated_at
                RETURNING user_id, scene_code, enabled, created_at, updated_at
                """, (rs, rowNum) -> new SceneRecord(
                rs.getLong("user_id"),
                rs.getString("scene_code"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()),
                userId, sceneCode, enabled, Timestamp.from(now), Timestamp.from(now));
    }

    public record SceneRecord(long userId,
                              String sceneCode,
                              boolean enabled,
                              Instant createdAt,
                              Instant updatedAt) {
    }
}
