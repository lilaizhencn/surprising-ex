package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.model.GatewayProductSetting;
import com.surprising.gateway.provider.repository.GatewayProductLineRepository;
import com.surprising.product.api.ProductLine;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 保存接入意图并原子审计；连接激活仍由 GatewayProductServices 承担。 */
@Service
public class GatewayProductSettings {
    private final GatewayProductLineRepository products;
    private final AdminAuditRepository audit;

    public GatewayProductSettings(GatewayProductLineRepository products, AdminAuditRepository audit) {
        this.products = products;
        this.audit = audit;
    }

    public List<GatewayProductSetting> list() {
        var settings = products.list();
        if (settings.size() != ProductLine.values().length)
            throw new IllegalStateException("产品线接入配置不完整，请检查数据库初始化");
        return settings;
    }

    @Transactional
    public GatewayProductSetting enable(ProductLine product, long expectedVersion, String reason, JwtPrincipal admin) {
        var enabled = products.enable(product, expectedVersion, Long.toString(admin.userId()), reason)
                .orElseThrow(() -> new OptimisticLockingFailureException("产品线配置已变更，请刷新后重试"));
        // 审计与接入意图在同一事务提交；审计失败不能启用连接。
        audit.recordRequired(new com.surprising.gateway.provider.auth.model.AdminOperationRecord(admin.userId(), admin.username(), admin.roles(),
                "product-lines", "POST", "/api/v1/admin/product-lines/" + product + "/enable",
                "version=" + enabled.configurationVersion() + "&reason=" + URLEncoder.encode(reason, StandardCharsets.UTF_8),
                "database:gateway_product_lines", null, 200, 0L, true, null,
                null, null, Instant.now()));
        return enabled;
    }
}
