package com.surprising.price.settings.controller;

import com.surprising.price.settings.model.PriceBusinessSettings;
import com.surprising.price.settings.model.PriceSettingsSnapshot;
import com.surprising.price.settings.service.PriceBusinessSettingsService;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 请求字段和类型校验、表单提示及 HTTP 异常映射归协议入口。 */
@RestController
@RequestMapping("/api/v1/price/index/admin/business-settings")
public class PriceBusinessSettingsController {
    public record Field(String kind, double minimum, double maximum, String label, String help, JsonNode initial) { }
    public record Update(PriceBusinessSettings settings, Long expectedVersion, String reason) { }
    private final PriceBusinessSettingsService settings;
    private final ObjectMapper json;
    private final tools.jackson.databind.ObjectReader requests;
    private final Map<String, Field> fields;

    public PriceBusinessSettingsController(PriceBusinessSettingsService settings, ObjectMapper json) {
        this.settings = settings; this.json = json;
        // 请求小数按十进制保留；先转为 double 会把略大于 1 的比例舍入为 1 后错误放行。
        this.requests = json.reader().with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        var specs = new LinkedHashMap<String, Field>();
        var initial = json.valueToTree(PriceBusinessSettings.initial());
        add(specs, initial, "indexPollDelayMs", "integer", 50, 1000, "指数计算间隔（毫秒）", "每次计算指数之间的间隔");
        add(specs, initial, "indexMaxSourceAgeMs", "duration", 100, 600000, "指数行情有效时间（毫秒）", "超过此年龄的外部行情不参与计算");
        add(specs, initial, "indexOutlierThreshold", "decimal", 0, 1, "指数异常偏离比例", "0.01 表示与中位数偏离超过 1% 的来源将被排除");
        add(specs, initial, "indexMinValidSources", "integer", 1, 20, "默认最少有效来源", "合约专属来源数量优先，此值仅用于缺省");
        add(specs, initial, "indexScale", "integer", 0, 18, "指数计算小数位", "计算过程保留的小数位数");
        add(specs, initial, "conversionCacheTtlMs", "duration", 100, 3600000, "汇率转换缓存（毫秒）", "转换报价的缓存有效时间");
        add(specs, initial, "markPublishIntervalMs", "integer", 100, 1000, "标记价发布间隔（毫秒）", "标记价格计算与发布时间间隔");
        add(specs, initial, "markBasisWindowMs", "duration", 1000, 3600000, "标记价基差窗口（毫秒）", "用于平滑基差的滚动窗口");
        add(specs, initial, "markMaxInputAgeMs", "duration", 100, 600000, "标记价输入有效时间（毫秒）", "指数、盘口和成交输入的最大年龄");
        add(specs, initial, "markClampRatio", "decimal", 0, 1, "标记价限制比例", "0.03 表示相对指数上下限为 3%");
        add(specs, initial, "defaultFundingIntervalHours", "integer", 1, 168, "默认资金费周期（小时）", "合约已设置的资金费周期优先");
        add(specs, initial, "markScale", "integer", 0, 18, "标记价计算小数位", "计算过程保留的小数位数");
        add(specs, initial, "websocketEnabled", "boolean", 0, 0, "启用外部实时行情", "关闭时停止外部 WebSocket 连接");
        add(specs, initial, "restFallbackEnabled", "boolean", 0, 0, "启用 REST 行情备用源", "实时行情不可用时尝试合约配置的 REST 地址");
        add(specs, initial, "connectionRefreshDelayMs", "integer", 100, 60000, "行情连接配置刷新（毫秒）", "检查新增、修改或删除的合约行情源");
        add(specs, initial, "indexCoordinationEnabled", "boolean", 0, 0, "指数多实例协调", "开启后使用租约避免多实例重复发布");
        add(specs, initial, "indexLeaseDurationMs", "duration", 1000, 600000, "指数协调租约（毫秒）", "发布者持有租约的有效时间");
        add(specs, initial, "markCoordinationEnabled", "boolean", 0, 0, "标记价多实例协调", "开启后使用租约避免多实例重复发布");
        add(specs, initial, "markLeaseDurationMs", "duration", 1000, 600000, "标记价协调租约（毫秒）", "发布者持有租约的有效时间");
        add(specs, initial, "fiatEnabled", "boolean", 0, 0, "启用法币汇率", "启用前必须填写汇率地址、路径和报价币种");
        add(specs, initial, "fiatRefreshDelayMs", "integer", 1000, 86400000, "法币汇率刷新（毫秒）", "访问所配置法币汇率服务的间隔");
        add(specs, initial, "fiatStaleAfterMs", "duration", 1000, 604800000, "法币汇率有效期（毫秒）", "过期汇率不用于转换");
        add(specs, initial, "fiatBaseUrl", "text", 0, 2048, "法币汇率服务地址", "完整 HTTP(S) 服务地址；响应使用 Open ER 格式");
        add(specs, initial, "fiatPath", "text", 0, 1024, "法币汇率请求路径", "以 / 开头，可使用 {base} 代表基础币种");
        add(specs, initial, "fiatBaseCurrency", "text", 1, 16, "法币基础币种", "大写币种代码");
        add(specs, initial, "fiatQuoteCurrencies", "list", 0, 100, "法币报价币种", "以逗号分隔的大写币种代码");
        add(specs, initial, "stableEnabled", "boolean", 0, 0, "启用稳定币汇率", "需要同时启用法币汇率");
        add(specs, initial, "stableRefreshDelayMs", "integer", 1000, 86400000, "稳定币汇率刷新（毫秒）", "请求稳定币现货汇率的间隔");
        add(specs, initial, "stableStaleAfterMs", "duration", 1000, 604800000, "稳定币汇率有效期（毫秒）", "过期汇率不用于转换");
        add(specs, initial, "stableCurrency", "text", 1, 16, "稳定币币种", "大写资产代码");
        add(specs, initial, "stableFiatCurrency", "text", 1, 16, "稳定币计价币种", "大写法币代码");
        add(specs, initial, "stableBaseUrl", "text", 0, 2048, "稳定币汇率服务地址", "完整 HTTP(S) 服务地址");
        add(specs, initial, "stablePath", "text", 0, 1024, "稳定币汇率请求路径", "以 / 开头的现货报价接口路径");
        add(specs, initial, "stableParser", "text", 1, 64, "稳定币报价解析器", "使用系统支持的现货解析器名称");
        add(specs, initial, "stableFallbackRate", "decimal", 0, 1000000, "稳定币备用汇率", "0 表示禁用备用；大于 0 时仅在无任何缓存且请求失败时使用");
        fields = Collections.unmodifiableMap(specs);
    }
    private void add(Map<String, Field> specs, JsonNode initial, String key, String kind,
                     double minimum, double maximum, String label, String help) {
        specs.put(key, new Field(kind, minimum, maximum, label, help, initial.get(key)));
    }

    @GetMapping
    public Map<String, Object> current(@RequestHeader("X-Admin-User-Id") String admin) {
        return Map.of("config", settings.current(), "fields", fields);
    }

    @PostMapping
    public PriceSettingsSnapshot update(@RequestHeader("X-Admin-User-Id") String admin, @RequestBody String raw) {
        try {
            JsonNode body = requests.readTree(raw);
            requireKeys(body, Set.of("settings", "expectedVersion", "reason"));
            if (!body.get("expectedVersion").isIntegralNumber() || !body.get("expectedVersion").canConvertToLong()
                    || !body.get("reason").isString()) throw new IllegalArgumentException("invalid version or reason type");
            long version = body.get("expectedVersion").asLong();
            String reason = body.get("reason").asString();
            if (version < 1 || admin == null || !admin.matches("[1-9][0-9]*") || reason.isBlank() || reason.length() > 1000)
                throw new IllegalArgumentException("expectedVersion, administrator and reason (1-1000 characters) are required");
            var values = body.get("settings");
            requireKeys(values, fields.keySet());
            fields.forEach((key, field) -> {
                var value = values.get(key);
                boolean valid = value != null && switch (field.kind()) {
                    case "boolean" -> value.isBoolean();
                    case "integer", "duration" -> value.isIntegralNumber() && value.canConvertToLong();
                    case "decimal" -> value.isNumber();
                    case "text" -> value.isString();
                    case "list" -> value.isArray();
                    default -> false;
                };
                if (!valid) throw new IllegalArgumentException(key + " has invalid type");
                if (field.kind().equals("list")) for (var item : value)
                    if (!item.isString()) throw new IllegalArgumentException(key + " must contain currency codes");
            });
            var candidate = json.treeToValue(body, Update.class).settings();
            return settings.save(candidate, version, admin, reason.trim());
        } catch (OptimisticLockingFailureException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid price settings: " + ex.getMessage(), ex);
        }
    }
    private void requireKeys(JsonNode value, Set<String> keys) {
        if (value == null || !value.isObject() || !new HashSet<>(value.propertyNames()).equals(keys))
            throw new IllegalArgumentException("all known settings are required; unknown keys rejected");
    }
}
