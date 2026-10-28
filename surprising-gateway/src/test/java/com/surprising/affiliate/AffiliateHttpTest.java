package com.surprising.affiliate;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.surprising.affiliate.controller.*;
import com.surprising.affiliate.service.*;
import com.surprising.gateway.provider.auth.service.AuthService;
import org.junit.jupiter.api.*;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class AffiliateHttpTest {

  AffiliateService service;
  AuthService auth;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    service = mock(AffiliateService.class);
    auth = mock(AuthService.class);
    mvc = MockMvcBuilders.standaloneSetup(
      new AffiliateController(auth, service, mock(AffiliateQueryService.class))
    )
      .setControllerAdvice(new AffiliateErrors())
      .build();
  }

  @Test
  void databaseDeadlineReturnsRecoverableMessageWithoutSqlDetails()
    throws Exception {
    when(service.preview("TEST")).thenThrow(
      new QueryTimeoutException("SELECT secret FROM private_table")
    );
    mvc
      .perform(get("/api/v1/affiliate/preview").param("code", "TEST"))
      .andExpect(status().isServiceUnavailable())
      .andExpect(
        jsonPath("$.message").value(
          "返佣资料暂不可用，请稍后刷新或联系平台核对"
        )
      );
  }

  @Test
  void busyReadReturnsExistingMessageContract() throws Exception {
    when(service.preview("TEST")).thenThrow(
      new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "返佣查询繁忙，请稍后重试"
      )
    );
    mvc
      .perform(get("/api/v1/affiliate/preview").param("code", "TEST"))
      .andExpect(status().isServiceUnavailable())
      .andExpect(jsonPath("$.message").value("返佣查询繁忙，请稍后重试"));
  }

  @Test
  void brokenHistoryFailsClosedWithoutPretendingToBeAnEmptyTeam()
    throws Exception {
    when(service.preview("TEST")).thenThrow(
      new IllegalStateException("团队历史资料不完整")
    );
    mvc
      .perform(get("/api/v1/affiliate/preview").param("code", "TEST"))
      .andExpect(status().isServiceUnavailable())
      .andExpect(jsonPath("$.message").isNotEmpty());
  }

  @Test
  void authenticationStatusRemainsUnauthorized() throws Exception {
    when(auth.authenticateBearer("Bearer invalid")).thenThrow(
      new IllegalArgumentException("登录已失效")
    );
    mvc
      .perform(
        get("/api/v1/affiliate/me").header("Authorization", "Bearer invalid")
      )
      .andExpect(status().isUnauthorized())
      .andExpect(jsonPath("$.message").value("登录已失效"));
    verifyNoInteractions(service);
  }
}
