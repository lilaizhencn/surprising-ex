package com.surprising.derivatives.lifecycle;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.adl.provider.controller.AdlController;
import com.surprising.adl.provider.service.AdlRuntimeConfigService;
import com.surprising.adl.provider.service.AdlService;
import com.surprising.funding.provider.controller.FundingController;
import com.surprising.funding.provider.service.FundingRuntimeConfigService;
import com.surprising.funding.provider.service.FundingService;
import com.surprising.insurance.provider.controller.InsuranceController;
import com.surprising.insurance.provider.service.InsuranceRuntimeConfigService;
import com.surprising.insurance.provider.service.InsuranceService;
import com.surprising.liquidation.provider.controller.LiquidationController;
import com.surprising.liquidation.provider.service.LiquidationRuntimeConfigService;
import com.surprising.liquidation.provider.service.LiquidationService;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.stubbing.Answer;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LifecycleSettingsControllerTest {
    enum Domain { FUNDING, LIQUIDATION, INSURANCE, ADL }
    private record Entry(Object controller,Object service,String path) { }
    private Entry entry(Domain domain,Answer<?> behavior) {
        return switch(domain) {
            case FUNDING -> {
                var service=mock(FundingRuntimeConfigService.class,behavior);
                yield new Entry(new FundingController(mock(FundingService.class),service),service,
                        com.surprising.funding.api.FundingApiPaths.API_V1+"/admin/runtime-config");
            }
            case LIQUIDATION -> {
                var service=mock(LiquidationRuntimeConfigService.class,behavior);
                yield new Entry(new LiquidationController(mock(LiquidationService.class),service),service,
                        com.surprising.liquidation.api.LiquidationApiPaths.BASE_PATH+"/admin/runtime-config");
            }
            case INSURANCE -> {
                var service=mock(InsuranceRuntimeConfigService.class,behavior);
                yield new Entry(new InsuranceController(mock(InsuranceService.class),service),service,
                        com.surprising.insurance.api.InsuranceApiPaths.API_V1+"/admin/runtime-config");
            }
            case ADL -> {
                var service=mock(AdlRuntimeConfigService.class,behavior);
                yield new Entry(new AdlController(mock(AdlService.class),service),service,
                        com.surprising.adl.api.AdlApiPaths.API_V1+"/admin/runtime-config");
            }
        };
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void validatesRequestMetadataBeforeBusinessAggregation(Domain domain) throws Exception {
        var entry=entry(domain,RETURNS_DEFAULTS);var mvc=MockMvcBuilders.standaloneSetup(entry.controller).build();
        String valid="{\"expectedVersion\":1,\"reason\":\"reason\"}";
        for (String bad : List.of("null","{}","{\"expectedVersion\":1}","{\"reason\":\"reason\"}",
                valid.replace(":1",":0"),valid.replace(":1",":-1"),valid.replace("\"reason\":\"reason\"","\"reason\":\" \""),
                valid.replace("\"reason\":\"reason\"","\"reason\":\""+"x".repeat(1001)+"\"")))
            mvc.perform(post(entry.path).header("X-Admin-User-Id","42").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        for(String admin:List.of("0","-1","user"))
            mvc.perform(post(entry.path).header("X-Admin-User-Id",admin).contentType(MediaType.APPLICATION_JSON).content(valid))
                    .andExpect(status().isBadRequest());
        if (domain==Domain.FUNDING || domain==Domain.ADL) {
            String field=domain==Domain.FUNDING?"paymentPageSize":"minDeficitAgeMs";
            mvc.perform(post(entry.path).header("X-Admin-User-Id","42").contentType(MediaType.APPLICATION_JSON)
                    .content(valid.replace("}",",\""+field+"\":1}"))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(entry.service);
        mvc.perform(post(entry.path).header("X-Admin-User-Id","42").contentType(MediaType.APPLICATION_JSON)
                .content(valid.replace("reason\"}","  reason  \"}"))).andExpect(status().isOk());
        var invocations=mockingDetails(entry.service).getInvocations();assertThat(invocations).hasSize(1);
        var args=invocations.iterator().next().getArguments();
        assertThat(args[0]).isEqualTo("42");assertThat(args[1]).isEqualTo(1L);assertThat(args[args.length-1]).isEqualTo("reason");
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void mapsBusinessVersionConflictsAtTheHttpBoundary(Domain domain) throws Exception {
        var entry=entry(domain,invocation->{ throw new OptimisticLockingFailureException("changed"); });
        var mvc=MockMvcBuilders.standaloneSetup(entry.controller).build();
        mvc.perform(post(entry.path).header("X-Admin-User-Id","42").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\"reason\"}")).andExpect(status().isConflict());
    }
}
