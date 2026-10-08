package com.surprising.gateway.provider.product;

import com.surprising.gateway.provider.auth.AdminAuditRepository;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 数据库是产品线接入意图的唯一来源；本机连接是否就绪由 GatewayProductServices 报告。 */
@Service
public class GatewayProductSettings {
    public record Setting(ProductLine productLine, boolean enabled, long configurationVersion,
                          String updatedBy, String reason, Instant updatedAt) { }
    public record Enable(Long expectedVersion, String reason) { }
    private final JdbcTemplate jdbc;
    private final AdminAuditRepository audit;

    public GatewayProductSettings(JdbcTemplate jdbc, AdminAuditRepository audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<Setting> list() {
        var settings = jdbc.query("SELECT * FROM gateway_product_lines ORDER BY product_line", (rs, n) ->
                new Setting(ProductLine.valueOf(rs.getString("product_line")), rs.getBoolean("enabled"),
                        rs.getLong("version"), rs.getString("updated_by"), rs.getString("reason"),
                        rs.getTimestamp("updated_at").toInstant()));
        if (settings.size() != ProductLine.values().length)
            throw new IllegalStateException("产品线接入配置不完整，请检查数据库初始化");
        return settings;
    }

    public Enable parseEnable(tools.jackson.databind.JsonNode body) {
        if (body == null || !body.isObject()
                || !new java.util.HashSet<>(body.propertyNames()).equals(java.util.Set.of("expectedVersion", "reason"))
                || !body.get("expectedVersion").isIntegralNumber() || !body.get("expectedVersion").canConvertToLong()
                || !body.get("reason").isString())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只接受整数 expectedVersion 和文本 reason");
        return new Enable(body.get("expectedVersion").asLong(), body.get("reason").asString());
    }

    @Transactional
    public Setting enable(ProductLine product, Enable request, JwtPrincipal admin) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 1
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要配置版本和 1 至 1000 字的修改原因");
        var rows = jdbc.query("""
                UPDATE gateway_product_lines SET enabled=true,version=version+1,updated_by=?,reason=?,updated_at=now()
                WHERE product_line=? AND version=? RETURNING *
                """, (rs, n) -> new Setting(product, rs.getBoolean("enabled"), rs.getLong("version"),
                        rs.getString("updated_by"), rs.getString("reason"), rs.getTimestamp("updated_at").toInstant()),
                Long.toString(admin.userId()), request.reason().trim(), product.name(), request.expectedVersion());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "产品线配置已变更，请刷新后重试");
        // 审计与接入意图在同一事务提交；审计失败不能启用连接。
        audit.recordRequired(new AdminAuditRepository.AdminOperationRecord(admin.userId(), admin.username(), admin.roles(),
                "product-lines", "POST", "/api/v1/admin/product-lines/" + product + "/enable",
                "version=" + rows.getFirst().configurationVersion() + "&reason=" + java.net.URLEncoder.encode(request.reason().trim(), java.nio.charset.StandardCharsets.UTF_8),
                "database:gateway_product_lines", null, 200, 0L, true, null,
                null, null, null, Instant.now()));
        return rows.getFirst();
    }
}
