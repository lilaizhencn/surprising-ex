package com.surprising.gateway.provider.announcement;

import com.surprising.gateway.provider.exception.AdminApprovalRequiredException;
import com.surprising.gateway.provider.auth.model.AdminRequestMetadata;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.surprising.gateway.provider.announcement.controller.*;
import com.surprising.gateway.provider.announcement.exception.AnnouncementProblem;
import com.surprising.gateway.provider.announcement.exception.AnnouncementExceptionHandler;
import com.surprising.gateway.provider.announcement.model.AnnouncementModels.*;
import com.surprising.gateway.provider.announcement.service.AnnouncementService;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class AnnouncementControllerTest {
    private final AnnouncementService service=mock(AnnouncementService.class);
    private final AdminApprovalService approvals=mock(AdminApprovalService.class);
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private final MockMvc mvc=MockMvcBuilders.standaloneSetup(new AdminAnnouncementController(service,approvals,json),new ClientAnnouncementController(service))
            .setControllerAdvice(new AnnouncementExceptionHandler()).build();
    private SaveRequest valid() { return new SaveRequest("GENERAL",10,List.of("CENTER"),List.of("SPOT"),List.of("WEB"),Instant.parse("2026-10-01T00:00:00Z"),null,List.of(new TranslationInput("en-US","Title","Summary","Body",null)),null); }

    @Test void malformedDraftsNeverReachBusinessAggregation() throws Exception {
        String valid=json.writeValueAsString(valid());
        for(String bad:List.of("null","{}",valid.replace("GENERAL","OTHER"),valid.replace("\"priority\":10","\"priority\":101"),
                valid.replace("\"startsAt\":\"2026-10-01T00:00:00Z\"","\"startsAt\":null"),
                valid.replace("\"placements\":[\"CENTER\"]","\"placements\":[\"ALL\",\"CENTER\"]"),
                valid.replace("\"locale\":\"en-US\"","\"locale\":\"invalid%%\""),
                valid.replace("\"title\":\"Title\"","\"title\":\" \""),
                valid.replace("\"body\":\"Body\"","\"body\":\" \""))) {
            assertThat(bad).isNotEqualTo(valid);
            mvc.perform(post("/api/v1/admin/announcements").header("Authorization","admin").contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void requestNormalizationProducesNativeImmutableContent() throws Exception {
        var request=new SaveRequest(" general ",null,List.of(" center ","CENTER"),List.of("spot"),null,valid().startsAt(),null,
                List.of(new TranslationInput("zh_hans"," 标题 ",null," 正文 ",null)),null);
        mvc.perform(post("/api/v1/admin/announcements").header("Authorization","admin").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andExpect(status().isOk());
        var content=ArgumentCaptor.forClass(Content.class);verify(service).create(eq("admin"),content.capture());
        assertThat(content.getValue().category()).isEqualTo("GENERAL");assertThat(content.getValue().priority()).isZero();
        assertThat(content.getValue().placements()).containsExactly("CENTER");assertThat(content.getValue().platforms()).containsExactly("ALL");
        assertThat(content.getValue().translations()).containsExactly(new Translation("zh-CN","标题","","正文",null));
        assertThatThrownBy(()->content.getValue().placements().add("BANNER")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void validatesPaginationAndTargetsAndNormalizesClientScopeAtTheController() throws Exception {
        for(String query:List.of("offset=-1","limit=101","locale=invalid%%","productLine=OTHER","platform=OTHER","placement=OTHER"))
            mvc.perform(get("/api/v1/announcements?"+query)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(get("/api/v1/announcements").param("locale","zh_hans").param("productLine","spot").param("platform","web").param("placement","center").param("offset","1").param("limit","3")).andExpect(status().isOk());
        verify(service).clientList(null,new ClientFilter("zh-CN","zh","SPOT","WEB","CENTER"),1,3);
    }
    @Test void keepsApprovalMetadataAndExactOriginalRequestBytes() throws Exception {
        when(approvals.approvalHeaderName()).thenReturn("X-Admin-Approval-Id");
        byte[] body=" { \"reason\": \"批准\", \"version\": 3 }\n".getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/api/v1/admin/announcements/7/publish?locale=zh-CN").header("Authorization","admin").header("X-Admin-Approval-Id","approval-7")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        var bytes=ArgumentCaptor.forClass(byte[].class);var metadata=ArgumentCaptor.forClass(AdminRequestMetadata.class);
        verify(service).publish(eq("admin"),eq(7L),eq(3L),eq("批准"),metadata.capture(),bytes.capture());
        assertThat(bytes.getValue()).containsExactly(body);
        assertThat(metadata.getValue()).isEqualTo(new AdminRequestMetadata(com.surprising.gateway.provider.auth.controller.AdminApprovalRequestBinding.reference("approval-7"),"POST","/api/v1/admin/announcements/7/publish","locale=zh-CN"));
    }
    @Test void mapsNativeBusinessFailuresAndPreservesRequiredApprovalStatus() throws Exception {
        int[] statuses={400,404,409,401,403};int i=0;
        for(var kind:AnnouncementProblem.Kind.values()) {
            doThrow(new AnnouncementProblem(kind,"domain failure")).when(service).adminGet("admin",7);
            mvc.perform(get("/api/v1/admin/announcements/7").header("Authorization","admin")).andExpect(status().is(statuses[i++])).andExpect(jsonPath("$.message").value("domain failure"));
        }
        when(approvals.approvalHeaderName()).thenReturn("X-Admin-Approval-Id");
        when(service.publish(eq("admin"),eq(7L),eq(3L),anyString(),any(),any())).thenThrow(new AdminApprovalRequiredException("approval required"));
        mvc.perform(post("/api/v1/admin/announcements/7/publish").header("Authorization","admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"publish\",\"version\":3}")).andExpect(status().isPreconditionRequired());
    }
}
