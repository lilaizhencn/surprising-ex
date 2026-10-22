package com.surprising.marketmaker.provider.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.repository.InMemoryMarketMakerRunEventRepository;
import com.surprising.marketmaker.provider.repository.MarketMakerRunEventRepository.MarketMakerRunEventWrite;
import com.surprising.marketmaker.provider.service.MarketMakerService;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual HTTP controller -> business view -> bounded in-memory repository. */
class MakerRunEventBoundaryTest {
    private static final Instant TIME=Instant.parse("2026-10-22T07:00:00.123456789Z");
    private final ObjectMapper json=new ObjectMapper();
    private MockMvc mvc;
    @BeforeEach void setup() {
        var store=new InMemoryMarketMakerRunEventRepository();
        for(ProductLine line:ProductLine.values()) {
            for(int i=0;i<3;i++)store.record(new MarketMakerRunEventWrite("maker",line,"1",7L,"node",i,"CYCLE_SUCCESS",1,0,0,null,null,i==2?TIME.plusNanos(1):TIME));
            store.record(new MarketMakerRunEventWrite("maker",line,"2",8L,"node",99,"CYCLE_ERROR",0,0,1,null,null,TIME));
        }
        var service=new MarketMakerService(new MarketMakerProperties(),null,null,null,null,null,null,null,store,null,null,null);
        mvc=MockMvcBuilders.standaloneSetup(new AdminMarketMakerController(service)).build();
    }
    static Stream<Arguments> pages() {
        return Arrays.stream(ProductLine.values()).flatMap(line->Stream.of("asc","desc").map(order->Arguments.of(line,order)));
    }
    @ParameterizedTest @MethodSource("pages")
    void fullInstantAndTiedIdsContinueWithoutCrossingProducts(ProductLine line,String order) throws Exception {
        JsonNode first=page(line,order,null);
        JsonNode second=page(line,order,first.get("nextCursor").asString());
        List<Long> cycles=new ArrayList<>();
        for(JsonNode page:List.of(first,second))for(JsonNode row:page.get("events")) {
            cycles.add(row.get("cycleSequence").asLong());
            assertThat(row.get("productLine").asString()).isEqualTo(line.name());
            assertThat(row.get("accountId").asLong()).isEqualTo(7);
            assertThat(row.get("instrumentId").asString()).isEqualTo("1");
        }
        assertThat(cycles).containsExactlyElementsOf(order.equals("asc")?List.of(0L,1L,2L):List.of(2L,1L,0L));
        assertThat(first.get("hasMore").asBoolean()).isTrue();assertThat(second.get("hasMore").asBoolean()).isFalse();
    }
    @ParameterizedTest @CsvSource({"0,1","-1,1","1,1","2000,1000"})
    void limitKeepsOriginalClampPolicy(int requested,int expected) throws Exception {
        var result=mvc.perform(get("/api/v1/admin/market-maker/strategy-logs").header("X-Admin-User-Id","9").param("limit",Integer.toString(requested)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsByteArray()).get("limit").asInt()).isEqualTo(expected);
    }
    @ParameterizedTest @ValueSource(strings={"sort","cursor","timestamp","instrument"})
    void malformedWireFieldsAreRejectedAtTheController(String invalid) throws Exception {
        var request=get("/api/v1/admin/market-maker/strategy-logs").header("X-Admin-User-Id","9");
        switch(invalid) {
            case "sort" -> request.param("sort","createdAt;drop");
            case "cursor" -> request.param("cursor","broken");
            case "timestamp" -> request.param("cursor",Base64.getUrlEncoder().withoutPadding().encodeToString("invalid|7".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            default -> request.param("instrumentId","bad");
        }
        mvc.perform(request).andExpect(status().isBadRequest());
    }
    private JsonNode page(ProductLine line,String order,String cursor) throws Exception {
        var request=get("/api/v1/admin/market-maker/strategy-logs").header("X-Admin-User-Id","9").param("productLine",line.name())
                .param("strategyId"," maker ").param("instrumentId"," 1 ").param("accountId","7").param("eventType"," cycle_success ")
                .param("limit","2").param("sort","createdAt."+order);
        if(cursor!=null)request.param("cursor",cursor);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
}
