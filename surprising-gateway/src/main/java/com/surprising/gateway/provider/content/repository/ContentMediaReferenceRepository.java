package com.surprising.gateway.provider.content.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContentMediaReferenceRepository {
    private final JdbcTemplate jdbc;
    public ContentMediaReferenceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean exists(UUID mediaId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM gateway_content_media_references WHERE media_id=?)", Boolean.class, mediaId));
    }
    public void replace(String ownerType, long ownerId, List<UUID> mediaIds) {
        if (!List.of("ANNOUNCEMENT", "LEGAL").contains(ownerType) || ownerId < 1) throw new IllegalArgumentException("invalid article owner");
        jdbc.update("DELETE FROM gateway_content_media_references WHERE owner_type=? AND owner_id=?", ownerType, ownerId);
        jdbc.batchUpdate("INSERT INTO gateway_content_media_references(owner_type,owner_id,media_id) VALUES (?,?,?)",
                mediaIds, 100, (statement, id) -> { statement.setString(1, ownerType); statement.setLong(2, ownerId); statement.setObject(3, id); });
    }
}
