package com.surprising.affiliate.controller;

import com.surprising.affiliate.model.AffiliateModels.*;
import com.surprising.affiliate.service.*;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.product.api.ProductLine;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/affiliate")
public class AffiliateAdminController {

  private final AuthService auth;
  private final AffiliateService service;
  private final AffiliateQueryService queries;
  private final AffiliateSettlementService settlement;

  public AffiliateAdminController(
    AuthService auth,
    AffiliateService service,
    AffiliateQueryService queries,
    AffiliateSettlementService settlement
  ) {
    this.auth = auth;
    this.service = service;
    this.queries = queries;
    this.settlement = settlement;
  }

  @GetMapping
  public Map<String, Object> overview(
    @RequestHeader("Authorization") String token,
    @RequestParam(defaultValue = "0") String cursor
  ) {
    admin(token, "read");
    return queries.adminOverview(
      cursor.equals("0") ? 0 : AffiliateService.positive(cursor)
    );
  }

  @PostMapping("/settings")
  public Settings settings(
    @RequestHeader("Authorization") String token,
    @Valid @RequestBody SettingsInput input
  ) {
    return service.configure(input, admin(token, "write"));
  }

  @PostMapping("/users/{userId}")
  public Profile configure(
    @RequestHeader("Authorization") String token,
    @PathVariable String userId,
    @Valid @RequestBody ProfileInput input
  ) {
    return service.configure(
      AffiliateService.positive(userId),
      input,
      admin(token, "write")
    );
  }

  @GetMapping("/users/{userId}")
  public Map<String, Object> user(
    @RequestHeader("Authorization") String token,
    @PathVariable String userId,
    @RequestParam(defaultValue = "") String productLine
  ) {
    admin(token, "read");
    long id = AffiliateService.positive(userId);
    service.register(id, null);
    return service.overview(id, productLine);
  }

  @PostMapping("/budgets/{product}")
  public Map<String, Boolean> budget(
    @RequestHeader("Authorization") String token,
    @PathVariable ProductLine product,
    @Valid @RequestBody BudgetInput input
  ) {
    long actor = admin(token, "write");
    service.budget(
      product.name(),
      AffiliateService.positive(input.userId()),
      actor,
      input.reason()
    );
    return Map.of("saved", true);
  }

  @PostMapping("/settle")
  public Map<String, Boolean> settle(
    @RequestHeader("Authorization") String token
  ) {
    settlement.prepare(true, admin(token, "write"));
    return Map.of("queued", true);
  }

  @PostMapping("/payouts/{id}/retry")
  public Map<String, Boolean> retry(
    @RequestHeader("Authorization") String token,
    @PathVariable UUID id
  ) {
    settlement.retry(id, admin(token, "write"));
    return Map.of("queued", true);
  }

  private long admin(String token, String action) {
    try {
      return auth
        .requireAdminPermission(token, "admin.affiliate." + action)
        .userId();
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(
        HttpStatus.UNAUTHORIZED,
        ex.getMessage(),
        ex
      );
    } catch (IllegalStateException ex) {
      throw new ResponseStatusException(
        HttpStatus.FORBIDDEN,
        ex.getMessage(),
        ex
      );
    }
  }
}
