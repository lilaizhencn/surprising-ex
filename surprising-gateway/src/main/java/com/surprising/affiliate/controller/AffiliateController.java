package com.surprising.affiliate.controller;

import com.surprising.affiliate.model.AffiliateModels.*;
import com.surprising.affiliate.service.AffiliateService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.model.CurrentUser;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/affiliate")
public class AffiliateController {

  private final AffiliateService service;
  private final com.surprising.affiliate.service.AffiliateQueryService queries;

  @org.springframework.beans.factory.annotation.Autowired
  public AffiliateController(
    AffiliateService service,
    com.surprising.affiliate.service.AffiliateQueryService queries
  ) {
    this.service = service;
    this.queries = queries;
  }

  public AffiliateController(
    AuthService auth,
    AffiliateService service,
    com.surprising.affiliate.service.AffiliateQueryService queries
  ) {
    this(service, queries);
  }

  @GetMapping("/preview")
  public Invitation preview(@RequestParam String code) {
    return service.preview(code);
  }

  @GetMapping("/me")
  public Map<String, Object> me(
    @CurrentUser long user,
    @RequestParam(defaultValue = "") String productLine
  ) {
    service.register(user, null);
    return service.overview(user, productLine);
  }

  @PostMapping("/accept")
  public Map<String, Boolean> accept(
    @CurrentUser long user,
    @Valid @RequestBody AcceptInput input
  ) {
    service.register(user, null);
    service.accept(user, input.code());
    return Map.of("accepted", true);
  }

  @PostMapping("/invitations")
  public Invitation invite(
    @CurrentUser long user,
    @Valid @RequestBody InviteInput input
  ) {
    return service.invite(user, input);
  }

  @GetMapping("/members")
  public Page<Map<String, Object>> members(
    @CurrentUser long user,
    @RequestParam(required = false) String parentId,
    @RequestParam(defaultValue = "0") String cursor
  ) {
    service.register(user, null);
    return service.members(
      user,
      parentId == null ? user : AffiliateService.positive(parentId),
      cursor.equals("0") ? 0 : AffiliateService.positive(cursor)
    );
  }

  @GetMapping("/members/{memberId}")
  public Map<String, Object> member(
    @CurrentUser long user,
    @PathVariable String memberId,
    @RequestParam(defaultValue = "") String productLine
  ) {
    return service.branchOverview(
      user,
      AffiliateService.positive(memberId),
      productLine
    );
  }

  @PostMapping("/members/{memberId}/share")
  public Map<String, Boolean> share(
    @CurrentUser long user,
    @PathVariable String memberId,
    @Valid @RequestBody ShareInput input
  ) {
    service.share(
      user,
      AffiliateService.positive(memberId),
      input.sharePpm()
    );
    return Map.of("saved", true);
  }

  @GetMapping("/entries")
  public Page<Map<String, Object>> entries(
    @CurrentUser long user,
    @RequestParam(defaultValue = "") String productLine,
    @RequestParam(defaultValue = "0") String cursor
  ) {
    return queries.entries(
      user,
      productLine,
      cursor.equals("0") ? 0 : AffiliateService.positive(cursor)
    );
  }

  @GetMapping("/payouts")
  public Map<String, Object> payouts(@CurrentUser long user) {
    return queries.payouts(user);
  }
}
