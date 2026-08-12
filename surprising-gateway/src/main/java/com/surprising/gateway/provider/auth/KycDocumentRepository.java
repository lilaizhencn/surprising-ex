package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.KycDocument;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KycDocumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public KycDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public KycDocument insert(long userId,
                              String documentType,
                              String originalFilename,
                              String contentType,
                              long fileSize,
                              String sha256,
                              String objectKey,
                              Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_user_kyc_documents (
                    user_id, document_type, original_filename, content_type, file_size, sha256,
                    object_key, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'UPLOADED', ?)
                RETURNING document_id, user_id, document_type, original_filename, content_type,
                          file_size, sha256, status, created_at, deleted_at
                """, (rs, rowNum) -> toDocument(rs, rowNum), userId, documentType, originalFilename, contentType,
                fileSize, sha256, objectKey, Timestamp.from(now));
    }

    public List<KycDocument> findForUser(long userId) {
        return jdbcTemplate.query("""
                SELECT document_id, user_id, document_type, original_filename, content_type,
                       file_size, sha256, status, created_at, deleted_at
                  FROM gateway_user_kyc_documents
                 WHERE user_id = ? AND status <> 'DELETED'
                 ORDER BY created_at DESC, document_id DESC
                """, (rs, rowNum) -> toDocument(rs, rowNum), userId);
    }

    public KycDocument findForUser(long userId, long documentId) {
        return jdbcTemplate.query("""
                SELECT document_id, user_id, document_type, original_filename, content_type,
                       file_size, sha256, status, created_at, deleted_at
                  FROM gateway_user_kyc_documents
                 WHERE user_id = ? AND document_id = ? AND status <> 'DELETED'
                """, (rs, rowNum) -> toDocument(rs, rowNum), userId, documentId).stream().findFirst().orElse(null);
    }

    public KycDocument findForAdmin(long userId, long documentId) {
        return jdbcTemplate.query("""
                SELECT document_id, user_id, document_type, original_filename, content_type,
                       file_size, sha256, status, created_at, deleted_at
                  FROM gateway_user_kyc_documents
                 WHERE user_id = ? AND document_id = ? AND status <> 'DELETED'
                """, (rs, rowNum) -> toDocument(rs, rowNum), userId, documentId).stream().findFirst().orElse(null);
    }

    public String objectKey(long userId, long documentId) {
        return jdbcTemplate.query("""
                SELECT object_key
                  FROM gateway_user_kyc_documents
                 WHERE user_id = ? AND document_id = ? AND status <> 'DELETED'
                """, (rs, rowNum) -> rs.getString("object_key"), userId, documentId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("KYC document not found"));
    }

    public List<KycDocument> findOwnedForSubmission(long userId, List<Long> documentIds) {
        List<Long> ids = normalizedIds(documentIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids.size() + 1);
        args.add(userId);
        args.addAll(ids);
        return jdbcTemplate.query("""
                SELECT document_id, user_id, document_type, original_filename, content_type,
                       file_size, sha256, status, created_at, deleted_at
                  FROM gateway_user_kyc_documents
                 WHERE user_id = ?
                   AND document_id IN (%s)
                   AND status IN ('UPLOADED', 'SUBMITTED')
                 ORDER BY document_id
                """.formatted(placeholders), (rs, rowNum) -> toDocument(rs, rowNum), args.toArray());
    }

    public int markSubmitted(long userId, List<Long> documentIds) {
        List<Long> ids = normalizedIds(documentIds);
        if (ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids.size() + 1);
        args.add(userId);
        args.addAll(ids);
        return jdbcTemplate.update("""
                UPDATE gateway_user_kyc_documents
                   SET status = 'SUBMITTED'
                 WHERE user_id = ?
                   AND document_id IN (%s)
                   AND status IN ('UPLOADED', 'SUBMITTED')
                """.formatted(placeholders), args.toArray());
    }

    private List<Long> normalizedIds(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        if (documentIds.size() > 10) {
            throw new IllegalArgumentException("at most 10 KYC documents may be submitted");
        }
        List<Long> ids = documentIds.stream().map(id -> {
            if (id == null || id <= 0) {
                throw new IllegalArgumentException("KYC document id must be positive");
            }
            return id;
        }).distinct().toList();
        if (ids.size() != documentIds.size()) {
            throw new IllegalArgumentException("KYC document ids must be unique");
        }
        return ids;
    }

    private KycDocument toDocument(ResultSet rs, int rowNum) throws SQLException {
        return new KycDocument(
                rs.getLong("document_id"),
                rs.getLong("user_id"),
                rs.getString("document_type"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getLong("file_size"),
                rs.getString("sha256"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                nullableInstant(rs, "deleted_at"));
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
