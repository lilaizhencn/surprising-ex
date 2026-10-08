package com.surprising.price.settings;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.mark.config.MarkPriceProperties;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 按产品线持久化的价格业务配置。字段目录同时驱动后台提示与接口校验。 */
@Service
public class PriceBusinessSettingsService {
    public record Field(String kind, double minimum, double maximum, String label, String help, JsonNode initial) {}
    public record Snapshot(long version, Map<String, JsonNode> settings, String updatedBy, String reason, Instant updatedAt) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IndexPriceProperties index;
    private final MarkPriceProperties mark;
    private final String line;
    private final Map<String, Field> fields;
    private volatile Snapshot current;
    private final ApplicationEventPublisher events;

    public PriceBusinessSettingsService(JdbcTemplate jdbc, ObjectMapper json, IndexPriceProperties index,
            MarkPriceProperties mark, ApplicationEventPublisher events) {
        this.events = events;
        this.jdbc = jdbc; this.json = json; this.index = index; this.mark = mark;
        this.line = index.getKafka().getProductLine().name();
        var specs = new LinkedHashMap<String, Field>();
        add(specs, "indexPollDelayMs", "integer", 50, 1000, "指数计算间隔（毫秒）", "每次计算指数之间的间隔", "1000");
        add(specs, "indexMaxSourceAgeMs", "duration", 100, 600000, "指数行情有效时间（毫秒）", "超过此年龄的外部行情不参与计算", "5000");
        add(specs, "indexOutlierThreshold", "decimal", 0, 1, "指数异常偏离比例", "0.01 表示与中位数偏离超过 1% 的来源将被排除", "0.01");
        add(specs, "indexMinValidSources", "integer", 1, 20, "默认最少有效来源", "合约专属来源数量优先，此值仅用于缺省", "3");
        add(specs, "indexScale", "integer", 0, 18, "指数计算小数位", "计算过程保留的小数位数", "18");
        add(specs, "conversionCacheTtlMs", "duration", 100, 3600000, "汇率转换缓存（毫秒）", "转换报价的缓存有效时间", "30000");
        add(specs, "markPublishIntervalMs", "integer", 100, 1000, "标记价发布间隔（毫秒）", "标记价格计算与发布时间间隔", "1000");
        add(specs, "markBasisWindowMs", "duration", 1000, 3600000, "标记价基差窗口（毫秒）", "用于平滑基差的滚动窗口", "60000");
        add(specs, "markMaxInputAgeMs", "duration", 100, 600000, "标记价输入有效时间（毫秒）", "指数、盘口和成交输入的最大年龄", "15000");
        add(specs, "markClampRatio", "decimal", 0, 1, "标记价限制比例", "0.03 表示相对指数上下限为 3%", "0.03");
        add(specs, "defaultFundingIntervalHours", "integer", 1, 168, "默认资金费周期（小时）", "合约已设置的资金费周期优先", "8");
        add(specs, "markScale", "integer", 0, 18, "标记价计算小数位", "计算过程保留的小数位数", "18");
        add(specs, "websocketEnabled", "boolean", 0, 0, "启用外部实时行情", "关闭时停止外部 WebSocket 连接", "true");
        add(specs, "restFallbackEnabled", "boolean", 0, 0, "启用 REST 行情备用源", "实时行情不可用时尝试合约配置的 REST 地址", "false");
        add(specs, "connectionRefreshDelayMs", "integer", 100, 60000, "行情连接配置刷新（毫秒）", "检查新增、修改或删除的合约行情源", "1000");
        add(specs, "indexCoordinationEnabled", "boolean", 0, 0, "指数多实例协调", "开启后使用租约避免多实例重复发布", "true");
        add(specs, "indexLeaseDurationMs", "duration", 1000, 600000, "指数协调租约（毫秒）", "发布者持有租约的有效时间", "15000");
        add(specs, "markCoordinationEnabled", "boolean", 0, 0, "标记价多实例协调", "开启后使用租约避免多实例重复发布", "true");
        add(specs, "markLeaseDurationMs", "duration", 1000, 600000, "标记价协调租约（毫秒）", "发布者持有租约的有效时间", "15000");
        add(specs, "fiatEnabled", "boolean", 0, 0, "启用法币汇率", "启用前必须填写汇率地址、路径和报价币种", "false");
        add(specs, "fiatRefreshDelayMs", "integer", 1000, 86400000, "法币汇率刷新（毫秒）", "访问所配置法币汇率服务的间隔", "3600000");
        add(specs, "fiatStaleAfterMs", "duration", 1000, 604800000, "法币汇率有效期（毫秒）", "过期汇率不用于转换", "108000000");
        add(specs, "fiatBaseUrl", "text", 0, 2048, "法币汇率服务地址", "完整 HTTP(S) 服务地址；响应使用 Open ER 格式", "\"\"");
        add(specs, "fiatPath", "text", 0, 1024, "法币汇率请求路径", "以 / 开头，可使用 {base} 代表基础币种", "\"\"");
        add(specs, "fiatBaseCurrency", "text", 1, 16, "法币基础币种", "大写币种代码", "\"USD\"");
        add(specs, "fiatQuoteCurrencies", "list", 0, 100, "法币报价币种", "以逗号分隔的大写币种代码", "[]");
        add(specs, "stableEnabled", "boolean", 0, 0, "启用稳定币汇率", "需要同时启用法币汇率", "false");
        add(specs, "stableRefreshDelayMs", "integer", 1000, 86400000, "稳定币汇率刷新（毫秒）", "请求稳定币现货汇率的间隔", "10000");
        add(specs, "stableStaleAfterMs", "duration", 1000, 604800000, "稳定币汇率有效期（毫秒）", "过期汇率不用于转换", "300000");
        add(specs, "stableCurrency", "text", 1, 16, "稳定币币种", "大写资产代码", "\"USDT\"");
        add(specs, "stableFiatCurrency", "text", 1, 16, "稳定币计价币种", "大写法币代码", "\"USD\"");
        add(specs, "stableBaseUrl", "text", 0, 2048, "稳定币汇率服务地址", "完整 HTTP(S) 服务地址", "\"\"");
        add(specs, "stablePath", "text", 0, 1024, "稳定币汇率请求路径", "以 / 开头的现货报价接口路径", "\"\"");
        add(specs, "stableParser", "text", 1, 64, "稳定币报价解析器", "使用系统支持的现货解析器名称", "\"COINBASE_TICKER\"");
        add(specs, "stableFallbackRate", "decimal", 0, 1000000, "稳定币备用汇率", "0 表示禁用备用；大于 0 时仅在无任何缓存且请求失败时使用", "0");
        this.fields = Collections.unmodifiableMap(specs);
    }
    private void add(Map<String, Field> specs, String key, String kind, double minimum, double maximum, String label, String help, String initial) {
        specs.put(key, new Field(kind, minimum, maximum, label, help, json.readTree(initial)));
    }
    public Map<String, Field> fields() { return fields; }
    public Snapshot current() {
        if (current == null) throw new IllegalStateException("price settings not initialized");
        return new Snapshot(current.version(), copy(current.settings()), current.updatedBy(), current.reason(), current.updatedAt());
    }
    @PostConstruct public void initialize() {
        var initial = new LinkedHashMap<String, JsonNode>(); fields.forEach((key, field) -> initial.put(key, field.initial().deepCopy()));
        jdbc.update("INSERT INTO price_business_settings(product_line,settings,version,updated_by,reason) VALUES (?,?::jsonb,1,'SYSTEM','initial price settings') ON CONFLICT DO NOTHING", line, json.writeValueAsString(initial));
        reload();
    }
    public synchronized Snapshot save(Map<String, JsonNode> requested, Long expectedVersion, String admin, String reason) {
        var candidate = copy(requested); validate(candidate);
        if (expectedVersion == null || expectedVersion < 1 || admin == null || !admin.matches("[1-9][0-9]*")
                || reason == null || reason.isBlank() || reason.length() > 1000)
            throw new IllegalArgumentException("expectedVersion, administrator and reason (1-1000 characters) are required");
        var rows = jdbc.query("UPDATE price_business_settings SET settings=?::jsonb,version=version+1,updated_by=?,reason=?,updated_at=now() WHERE product_line=? AND version=? RETURNING version,settings,updated_by,reason,updated_at",
                (rs,row) -> decode(rs), json.writeValueAsString(candidate), admin, reason.trim(), line, expectedVersion);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "price settings changed; reload before saving");
        install(rows.getFirst()); return current();
    }
    @Scheduled(fixedDelay = 1000) public synchronized void reload() {
        var value = jdbc.queryForObject("SELECT version,settings,updated_by,reason,updated_at FROM price_business_settings WHERE product_line=?", (rs,row) -> decode(rs), line);
        if (value == null) throw new IllegalStateException("price settings missing");
        if (current == null || value.version() > current.version()) install(value);
    }
    private Snapshot decode(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Snapshot(rs.getLong("version"), json.readValue(rs.getString("settings"), new tools.jackson.core.type.TypeReference<Map<String, JsonNode>>() {}),
                rs.getString("updated_by"),rs.getString("reason"),rs.getTimestamp("updated_at").toInstant());
    }
    private Map<String, JsonNode> copy(Map<String, JsonNode> values) {
        if (values == null) throw new IllegalArgumentException("complete settings required");
        var copy = new LinkedHashMap<String, JsonNode>(); values.forEach((key,value) -> copy.put(key, value == null ? null : value.deepCopy())); return copy;
    }
    public void validate(Map<String, JsonNode> values) {
        if (values == null || !values.keySet().equals(fields.keySet())) throw new IllegalArgumentException("all known price settings are required; unknown keys are rejected");
        fields.forEach((key, field) -> {
            var value = values.get(key);
            if (value == null || value.isNull()) throw new IllegalArgumentException(key + " is required");
            boolean valid = switch (field.kind()) {
                case "boolean" -> value.isBoolean();
                case "integer", "duration" -> value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= field.minimum() && value.asLong() <= field.maximum();
                case "decimal" -> value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= field.minimum() && value.asDouble() <= field.maximum();
                case "text" -> value.isString() && value.asString().length() >= field.minimum() && value.asString().length() <= field.maximum();
                case "list" -> value.isArray() && value.size() <= field.maximum();
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException(key + ": " + field.label() + " invalid; range [" + field.minimum() + "," + field.maximum() + "]");
        });
        for (var key : List.of("fiatBaseCurrency", "stableCurrency", "stableFiatCurrency")) currency(values.get(key).asString());
        var seen = new HashSet<String>();
        for (var value : values.get("fiatQuoteCurrencies")) {
            if (!value.isString()) throw new IllegalArgumentException("fiatQuoteCurrencies must be currency codes");
            currency(value.asString()); if (!seen.add(value.asString())) throw new IllegalArgumentException("duplicate fiat currency");
        }
        if (values.get("fiatEnabled").asBoolean()) {
            endpoint(values, "fiat");
            if (seen.isEmpty()) throw new IllegalArgumentException("fiatQuoteCurrencies required when enabled");
        }
        if (values.get("stableEnabled").asBoolean()) {
            if (!values.get("fiatEnabled").asBoolean()) throw new IllegalArgumentException("stable feed requires fiat conversion enabled");
            endpoint(values, "stable");
        }
        if (!Set.of("COINBASE_TICKER", "BINANCE_BOOK_TICKER", "OKX_TICKER", "BYBIT_TICKER").contains(values.get("stableParser").asString()))
            throw new IllegalArgumentException("unsupported stableParser");
    }
    private static void currency(String value) {
        if (!value.matches("[A-Z][A-Z0-9]{1,15}")) throw new IllegalArgumentException("invalid currency: " + value);
    }
    private static void endpoint(Map<String, JsonNode> values, String prefix) {
        var uri = java.net.URI.create(values.get(prefix + "BaseUrl").asString());
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !values.get(prefix + "Path").asString().startsWith("/"))
            throw new IllegalArgumentException("invalid " + prefix + " feed endpoint");
    }
    private void install(Snapshot snapshot) {
        var values = copy(snapshot.settings()); validate(values);
        var ic = new IndexPriceProperties.Calculation(); var mc = new MarkPriceProperties.Calculation();
        var ws = json.convertValue(index.getWebSocket(), IndexPriceProperties.WebSocket.class);
        var fiat = new IndexPriceProperties.Fiat(); var stable = new IndexPriceProperties.StableCoin();
        var ix = new IndexPriceProperties.Coordination(); var mx = new MarkPriceProperties.Coordination();
        ix.setNodeId(index.getCoordination().getNodeId()); mx.setNodeId(mark.getCoordination().getNodeId());
        ic.setPollDelayMs(values.get("indexPollDelayMs").asLong());
        ic.setMaxSourceAge(Duration.ofMillis(values.get("indexMaxSourceAgeMs").asLong()));
        ic.setOutlierThreshold(values.get("indexOutlierThreshold").decimalValue());
        ic.setMinValidSources(values.get("indexMinValidSources").asInt());
        ic.setScale(values.get("indexScale").asInt());
        ic.setConversionCacheTtl(Duration.ofMillis(values.get("conversionCacheTtlMs").asLong()));
        mc.setPublishIntervalMs(values.get("markPublishIntervalMs").asLong());
        mc.setBasisWindow(Duration.ofMillis(values.get("markBasisWindowMs").asLong()));
        mc.setMaxInputAge(Duration.ofMillis(values.get("markMaxInputAgeMs").asLong()));
        mc.setClampRatio(values.get("markClampRatio").decimalValue());
        mc.setDefaultFundingIntervalHours(values.get("defaultFundingIntervalHours").asInt());
        mc.setScale(values.get("markScale").asInt());
        ws.setEnabled(values.get("websocketEnabled").asBoolean());
        ws.setRestFallbackEnabled(values.get("restFallbackEnabled").asBoolean());
        ws.setRefreshDelayMs(values.get("connectionRefreshDelayMs").asLong());
        ix.setEnabled(values.get("indexCoordinationEnabled").asBoolean());
        ix.setLeaseDuration(Duration.ofMillis(values.get("indexLeaseDurationMs").asLong()));
        mx.setEnabled(values.get("markCoordinationEnabled").asBoolean());
        mx.setLeaseDuration(Duration.ofMillis(values.get("markLeaseDurationMs").asLong()));
        fiat.setEnabled(values.get("fiatEnabled").asBoolean());
        fiat.setRefreshDelayMs(values.get("fiatRefreshDelayMs").asLong());
        fiat.setStaleAfter(Duration.ofMillis(values.get("fiatStaleAfterMs").asLong()));
        fiat.setBaseUrl(values.get("fiatBaseUrl").asString());
        fiat.setPath(values.get("fiatPath").asString());
        fiat.setBaseCurrency(values.get("fiatBaseCurrency").asString());
        fiat.setQuoteCurrencies(json.convertValue(values.get("fiatQuoteCurrencies"), new tools.jackson.core.type.TypeReference<java.util.List<String>>() {}));
        stable.setEnabled(values.get("stableEnabled").asBoolean());
        stable.setRefreshDelayMs(values.get("stableRefreshDelayMs").asLong());
        stable.setStaleAfter(Duration.ofMillis(values.get("stableStaleAfterMs").asLong()));
        stable.setCurrency(values.get("stableCurrency").asString());
        stable.setFiatCurrency(values.get("stableFiatCurrency").asString());
        stable.setBaseUrl(values.get("stableBaseUrl").asString());
        stable.setPath(values.get("stablePath").asString());
        stable.setParser(values.get("stableParser").asString());
        stable.setFallbackRate(values.get("stableFallbackRate").decimalValue());
        fiat.setStableCoin(stable);
        index.setCalculation(ic); index.setWebSocket(ws); index.setFiat(fiat); index.setCoordination(ix);
        mark.setCalculation(mc); mark.setCoordination(mx); current = snapshot;
        events.publishEvent(snapshot);
    }
}
