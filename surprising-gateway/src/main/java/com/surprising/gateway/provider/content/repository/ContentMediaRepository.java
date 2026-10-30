package com.surprising.gateway.provider.content.repository;

import com.surprising.gateway.provider.content.model.ContentMedia;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Only reads and writes gateway_content_media. */
@Repository
public class ContentMediaRepository {
    private final JdbcTemplate jdbc;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named;
    public ContentMediaRepository(JdbcTemplate jdbc) { this.jdbc = jdbc;this.named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc); }
    public List<ContentMedia> findFor(List<UUID> ids) {
        if(ids.isEmpty())return List.of();
        return named.query("SELECT * FROM gateway_content_media WHERE media_id IN (:ids)",Map.of("ids",ids),
                (rs,n)->new ContentMedia(rs.getObject("media_id",UUID.class),rs.getString("object_key"),rs.getString("content_type"),rs.getLong("size_bytes"),rs.getLong("uploaded_by"),rs.getTimestamp("created_at").toInstant()));
    }
    public List<UUID> lockForReference(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return named.query("SELECT media_id FROM gateway_content_media WHERE media_id IN (:ids) ORDER BY media_id FOR KEY SHARE",
                Map.of("ids", ids), (rs, row) -> rs.getObject("media_id", UUID.class));
    }
    public Optional<ContentMedia> lockOldUnreferenced(Instant cutoff) {
        return jdbc.query("""
                SELECT m.* FROM gateway_content_media m
                WHERE m.created_at < ? AND NOT EXISTS
                  (SELECT 1 FROM gateway_content_media_references r WHERE r.media_id=m.media_id)
                ORDER BY m.created_at,m.media_id LIMIT 1 FOR UPDATE OF m SKIP LOCKED
                """, (rs, row) -> new ContentMedia(rs.getObject("media_id", UUID.class),rs.getString("object_key"),
                rs.getString("content_type"),rs.getLong("size_bytes"),rs.getLong("uploaded_by"),rs.getTimestamp("created_at").toInstant()),
                Timestamp.from(cutoff)).stream().findFirst();
    }
    public void delete(UUID id) { jdbc.update("DELETE FROM gateway_content_media WHERE media_id=?", id); }
    public void insert(ContentMedia media) {
        jdbc.update("INSERT INTO gateway_content_media(media_id,object_key,content_type,size_bytes,uploaded_by,created_at) VALUES (?,?,?,?,?,?)",
                media.mediaId(), media.objectKey(), media.contentType(), media.size(), media.uploadedBy(), Timestamp.from(media.createdAt()));
    }
    public Optional<ContentMedia> find(UUID id) {
        return jdbc.query("SELECT * FROM gateway_content_media WHERE media_id=?", (rs, n) -> new ContentMedia(
                rs.getObject("media_id", UUID.class), rs.getString("object_key"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getLong("uploaded_by"), rs.getTimestamp("created_at").toInstant()), id).stream().findFirst();
    }
}
