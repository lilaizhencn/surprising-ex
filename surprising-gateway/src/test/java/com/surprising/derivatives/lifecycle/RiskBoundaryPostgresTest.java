package com.surprising.derivatives.lifecycle;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.RiskApiPaths;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.controller.*;
import com.surprising.risk.provider.repository.CoreRiskLiquidationProjectionRepository;
import com.surprising.risk.provider.service.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class RiskBoundaryPostgresTest {
    private static final long FIRST = 9007199254740993L;
    private static final long NOW = Instant.parse("2026-10-22T07:00:00.123Z").toEpochMilli();
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate admin, jdbc;
    private String schema;
    private RiskAeronGateway core;
    private CoreRiskLiquidationProjectionRepository projections;
    private MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL"), user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "risk_boundary_" + UUID.randomUUID().toString().replace("-", ""); admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password));
        String init = Files.readString(Path.of("../..", "init.sql")); int start = init.indexOf("CREATE TABLE IF NOT EXISTS core_liquidation_projection (");
        jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        start = init.indexOf("CREATE TABLE IF NOT EXISTS core_liquidation_projection_initialization (");
        jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        configure(ProductLine.LINEAR_PERPETUAL);
    }
    @AfterEach void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    private void configure(ProductLine line) {
        var properties = new RiskProperties(); properties.setProductLine(line); core = mock(RiskAeronGateway.class);
        when(core.riskState(anyLong())).thenReturn(List.of());
        projections = spy(new CoreRiskLiquidationProjectionRepository(jdbc, properties));
        var service = new RiskService(properties, core, projections, mock(InstrumentSnapshotCache.class));
        mvc = MockMvcBuilders.standaloneSetup(new AdminRiskController(service), new RiskController(service, new RiskRuntimeConfigService(service))).build();
    }
    static Stream<Arguments> pages() {
        return Arrays.stream(ProductLine.values()).flatMap(line -> Arrays.stream(LiquidationCandidateStatus.values())
                .flatMap(status -> Stream.of("asc", "desc").map(order -> Arguments.of(line, status, order))));
    }
    @Test void uninitializedIsUnavailableAndReadyEmptyRemainsAnEmptyResult() throws Exception {
        mvc.perform(get("/api/v1/admin/risk/liquidation-candidates").header("X-Admin-User-Id", "9"))
                .andExpect(status().isServiceUnavailable());
        jdbc.update("INSERT INTO core_liquidation_projection_initialization VALUES (?)", ProductLine.LINEAR_PERPETUAL.name());
        assertThat(candidates(LiquidationCandidateStatus.NEW, "asc", null).get("candidates").size()).isZero();
    }

    @Test void candidateUsesStoredMarginModeAndDoesNotInventAnInstrumentVersion() throws Exception {
        seed(ProductLine.LINEAR_PERPETUAL, LiquidationCandidateStatus.NEW);
        jdbc.update("UPDATE core_liquidation_projection SET margin_mode='ISOLATED'");
        var candidate = candidates(LiquidationCandidateStatus.NEW, "asc", null).get("candidates").get(0);
        assertThat(candidate.get("marginMode").asString()).isEqualTo("ISOLATED");
        assertThat(candidate.has("instrumentChangeId")).isFalse();
        for (String field : List.of("snapshotId", "markPriceTicks", "equityUnits", "maintenanceMarginUnits", "marginRatioPpm"))
            assertThat(candidate.hasNonNull(field)).as("missing current risk: %s", field).isFalse();
    }

    @Test void currentRiskMetricsDoNotReplaceCommittedCandidateFacts() throws Exception {
        seed(ProductLine.LINEAR_PERPETUAL, LiquidationCandidateStatus.NEW);
        var risk = mock(CoreRiskSnapshotView.class);
        when(risk.userId()).thenReturn(42L);
        when(risk.instrumentId()).thenReturn("1");
        when(risk.marginMode()).thenReturn(CoreMarginMode.CROSS);
        when(risk.positionSide()).thenReturn(CorePositionSide.NET);
        when(risk.priceSequence()).thenReturn(12L);
        when(risk.markPriceTicks()).thenReturn(500L);
        when(risk.equityUnits()).thenReturn(0L);
        when(risk.maintenanceMarginUnits()).thenReturn(20L);
        when(risk.marginRatioPpm()).thenReturn(1_000_000L);
        when(core.riskState(42)).thenReturn(List.of(risk));
        var candidate = candidates(LiquidationCandidateStatus.NEW, "asc", null).get("candidates").get(0);
        assertThat(candidate.get("snapshotId").asLong()).isEqualTo(12);
        assertThat(candidate.get("markPriceTicks").asLong()).isEqualTo(500);
        assertThat(candidate.get("equityUnits").asLong()).isZero();
        assertThat(candidate.get("maintenanceMarginUnits").asLong()).isEqualTo(20);
        assertThat(candidate.get("marginRatioPpm").asLong()).isEqualTo(1_000_000);
        assertThat(candidate.get("signedQuantitySteps").asLong()).isEqualTo(1);
        assertThat(candidate.get("settleAsset").asString()).isEqualTo("USDT");
        verify(risk, never()).signedQuantitySteps();
        verify(risk, never()).settleAsset();
    }

    @ParameterizedTest @MethodSource("pages")
    void nativeCandidateQueryKeepsProductsStatusesDirectionsAndLongIds(ProductLine line, LiquidationCandidateStatus status, String order) throws Exception {
        configure(line); seed(line, status); seed(ProductLine.values()[(line.ordinal() + 1) % ProductLine.values().length], status);
        JsonNode first = candidates(status, order, null);
        JsonNode next = candidates(status, order, first.get("nextCursor").asString());
        List<Long> ids = new ArrayList<>();
        for (JsonNode page : List.of(first, next)) for (JsonNode row : page.get("candidates")) {
            ids.add(row.get("candidateId").asLong());
            assertThat(row.get("status").asString()).isEqualTo(status.name());
            assertThat(row.get("accountType").asString()).isEqualTo(line.accountTypeCode());
        }
        long id = FIRST + line.ordinal() * 10;
        assertThat(ids).containsExactlyElementsOf(order.equals("asc") ? List.of(id,id+1,id+2) : List.of(id+2,id+1,id));
        assertThat(first.get("hasMore").asBoolean()).isTrue(); assertThat(next.get("hasMore").asBoolean()).isFalse();
        assertThat(first.get("sort").asString()).isEqualTo("eventTime." + order);
        verify(core, times(3)).riskState(42); verify(core, never()).updateRiskScanControl(any());
    }
    private JsonNode candidates(LiquidationCandidateStatus state, String order, String cursor) throws Exception {
        var request = get("/api/v1/admin/risk/liquidation-candidates").header("X-Admin-User-Id","9")
                .param("status", " " + state.name().toLowerCase() + " ").param("limit","2").param("sort","eventTime."+order);
        if (cursor != null) request.param("cursor",cursor);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    @ParameterizedTest @ValueSource(strings={"status", "limit0", "limit1001", "sort", "cursor"})
    void malformedCandidateInputNeverReachesRepositoryOrCore(String invalid) throws Exception {
        var request = get("/api/v1/admin/risk/liquidation-candidates").header("X-Admin-User-Id","9");
        switch(invalid) {
            case "status" -> request.param("status","bad"); case "limit0" -> request.param("limit","0");
            case "limit1001" -> request.param("limit","1001"); case "sort" -> request.param("sort","eventTime;drop");
            default -> request.param("cursor","broken");
        }
        mvc.perform(request).andExpect(status().isBadRequest()); verifyNoInteractions(projections,core);
    }
    @ParameterizedTest @ValueSource(strings={"rules", "runtime"})
    void bothRuleEntrypointsBindReasonAndPreserveCoreDefaults(String route) throws Exception {
        when(core.riskScanControl()).thenReturn(view(7, "existing", true, 1234, 111, "core", "initial"));
        when(core.updateRiskScanControl(any())).thenAnswer(call -> {
            UpdateRiskScanControlCommand command=call.getArgument(0);
            return view(8,command.ruleName(),command.enabled(),command.scanDelayMs(),command.scanBatchSize(),command.adminUserId(),command.reason());
        });
        var body=new LinkedHashMap<String,Object>();body.put("expectedVersion",7);body.put("reason","  review  ");
        if(route.equals("rules"))body.put("ruleName","  renamed  ");
        mvc.perform(post(updatePath(route)).header("X-Admin-User-Id"," 9 ").contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk());
        var command=ArgumentCaptor.forClass(UpdateRiskScanControlCommand.class);verify(core).updateRiskScanControl(command.capture());
        assertThat(command.getValue()).isEqualTo(new UpdateRiskScanControlCommand(7, route.equals("rules") ? "renamed" : "existing",true,1234,111,"9","review"));
        verifyNoInteractions(projections);
    }
    @ParameterizedTest @ValueSource(strings={"rules", "runtime"})
    void coreVersionConflictStillReturns409(String route) throws Exception {
        when(core.riskScanControl()).thenReturn(view(8,"existing",true,1234,111,"core","initial"));
        when(core.updateRiskScanControl(any())).thenThrow(new RiskAeronGateway.RiskScanControlConflictException("version conflict"));
        mvc.perform(post(updatePath(route)).header("X-Admin-User-Id","9").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("expectedVersion",7,"reason","review")))).andExpect(status().isConflict());
    }
    @ParameterizedTest @ValueSource(strings={"reason", "long-reason", "long-name", "expectedVersion", "scanDelayMs", "scanBatchSize", "batch-too-large"})
    void malformedRuleFieldsNeverReachCore(String invalid) throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("expectedVersion",7);body.put("reason","review");
        switch(invalid) {
            case "reason" -> body.put("reason"," "); case "long-reason" -> body.put("reason","x".repeat(501));
            case "long-name" -> body.put("ruleName","x".repeat(121));case "expectedVersion" -> body.put("expectedVersion",0);
            case "scanDelayMs" -> body.put("scanDelayMs",-1); case "scanBatchSize" -> body.put("scanBatchSize",0);
            default -> body.put("scanBatchSize",4097);
        }
        mvc.perform(post(updatePath("rules")).header("X-Admin-User-Id","9").contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest());verifyNoInteractions(core,projections);
    }
    @Test void globalMarginPolicyRemainsOwnedByCoreInstruments() throws Exception {
        mvc.perform(post("/api/v1/admin/risk/rules/global_margin_policy").header("X-Admin-User-Id","9").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("reason","review")))).andExpect(status().isBadRequest());verifyNoInteractions(core,projections);
    }
    @Test void assetBindingPrecedesReadsAndProductOwnershipStillGuardsCoreState() throws Exception {
        var user=mock(CoreUserStateView.class);var balance=mock(CoreBalanceView.class);
        when(user.productLine()).thenReturn(ProductLine.LINEAR_PERPETUAL);when(user.revision()).thenReturn(7L);
        when(user.balances()).thenReturn(List.of(balance));when(balance.asset()).thenReturn("USDT");
        when(balance.availableUnits()).thenReturn(11L);when(balance.lockedUnits()).thenReturn(2L);when(core.userState(42)).thenReturn(user);
        mvc.perform(get(RiskApiPaths.RISK_BASE_PATH+"/account/latest").param("userId","42").param("accountType"," usdt_perpetual ").param("settleAsset"," usdt "))
                .andExpect(status().isOk());
        when(user.productLine()).thenReturn(ProductLine.INVERSE_PERPETUAL);
        mvc.perform(get(RiskApiPaths.RISK_BASE_PATH+"/account/latest").param("userId","42").param("accountType","USDT_PERPETUAL").param("settleAsset","USDT"))
                .andExpect(status().isNotFound());
    }
    @Test void invalidRealtimeUserAndAssetAreBoundBeforeCoreAccess() throws Exception {
        mvc.perform(get(RiskApiPaths.RISK_BASE_PATH+"/positions/latest").param("userId","0")).andExpect(status().isBadRequest());
        mvc.perform(get(RiskApiPaths.RISK_BASE_PATH+"/account/latest").param("userId","42").param("accountType","USDT_PERPETUAL").param("settleAsset","bad!"))
                .andExpect(status().isBadRequest());verifyNoInteractions(core,projections);
    }
    private static String updatePath(String route) { return route.equals("rules") ? "/api/v1/admin/risk/rules/risk_scan_control" : RiskApiPaths.RISK_BASE_PATH+"/admin/runtime-config"; }
    private static CoreRiskScanControlView view(long version,String name,boolean enabled,long delay,int batch,String admin,String reason) {
        return new CoreRiskScanControlView(version,name,enabled,delay,batch,admin,reason,NOW);
    }
    private void seed(ProductLine line,LiquidationCandidateStatus status) {
        jdbc.update("INSERT INTO core_liquidation_projection_initialization VALUES (?) ON CONFLICT DO NOTHING", line.name());
        long id=FIRST+line.ordinal()*10;
        for(int i=0;i<3;i++) {
            String state=status==LiquidationCandidateStatus.PROCESSING ? List.of("ORDERED","INSURANCE_REQUIRED","ADL_REQUIRED").get(i)
                    : status==LiquidationCandidateStatus.NEW ? "PLANNED" : status.name();
            jdbc.update("INSERT INTO core_liquidation_projection VALUES(?,?,42,'1','USDT','CROSS','NET',1,1,1,0,0,?,1,?)",line.name(),id+i,state,NOW+i);
        }
        jdbc.update("INSERT INTO core_liquidation_projection VALUES(?,?,42,'1','USDT','CROSS','NET',1,1,1,0,0,?,1,?)",line.name(),id+9,status==LiquidationCandidateStatus.NEW ? "CANCELED" : "PLANNED",NOW);
    }
}
