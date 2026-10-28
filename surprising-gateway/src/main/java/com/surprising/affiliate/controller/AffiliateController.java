package com.surprising.affiliate.controller;

import com.surprising.affiliate.model.AffiliateModels.*;
import com.surprising.affiliate.service.AffiliateService;
import com.surprising.gateway.provider.auth.service.AuthService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/affiliate")
public class AffiliateController {

  private final AuthService auth;
  private final AffiliateService service;
  private final com.surprising.affiliate.service.AffiliateQueryService queries;

  public AffiliateController(
    AuthService auth,
    AffiliateService service,
    com.surprising.affiliate.service.AffiliateQueryService queries
  ) {
    this.auth = auth;
    this.service = service;
    this.queries = queries;
  }

  @GetMapping("/preview")
  public Invitation preview(@RequestParam String code) {
    return service.preview(code);
  }

  @GetMapping("/me")
  public Map<String, Object> me(
    @RequestHeader("Authorization") String token,
    @RequestParam(defaultValue = "") String productLine
  ) {
    long user = user(token);
    service.register(user, null);
    return service.overview(user, productLine);
  }

  @PostMapping("/accept")
  public Map<String, Boolean> accept(
    @RequestHeader("Authorization") String token,
    @Valid @RequestBody AcceptInput input
  ) {
    long user = user(token);
    service.register(user, null);
    service.accept(user, input.code());
    return Map.of("accepted", true);
  }

  @PostMapping("/invitations")
  public Invitation invite(
    @RequestHeader("Authorization") String token,
    @Valid @RequestBody InviteInput input
  ) {
    return service.invite(user(token), input);
  }

  @GetMapping("/members")
  public Page<Map<String, Object>> members(
    @RequestHeader("Authorization") String token,
    @RequestParam(required = false) String parentId,
    @RequestParam(defaultValue = "0") String cursor
  ) {
    long user = user(token);
    service.register(user, null);
    return service.members(
      user,
      parentId == null ? user : AffiliateService.positive(parentId),
      cursor.equals("0") ? 0 : AffiliateService.positive(cursor)
    );
  }

  @GetMapping("/members/{memberId}")
  public Map<String, Object> member(
    @RequestHeader("Authorization") String token,
    @PathVariable String memberId,
    @RequestParam(defaultValue = "") String productLine
  ) {
    return service.branchOverview(
      user(token),
      AffiliateService.positive(memberId),
      productLine
    );
  }

  @PostMapping("/members/{memberId}/share")
  public Map<String, Boolean> share(
    @RequestHeader("Authorization") String token,
    @PathVariable String memberId,
    @Valid @RequestBody ShareInput input
  ) {
    service.share(
      user(token),
      AffiliateService.positive(memberId),
      input.sharePpm()
    );
    return Map.of("saved", true);
  }

  @GetMapping("/entries")
  public Page<Map<String, Object>> entries(
    @RequestHeader("Authorization") String token,
    @RequestParam(defaultValue = "") String productLine,
    @RequestParam(defaultValue = "0") String cursor
  ) {
    return queries.entries(
      user(token),
      productLine,
      cursor.equals("0") ? 0 : AffiliateService.positive(cursor)
    );
  }

  @GetMapping("/payouts")
  public Map<String, Object> payouts(
    @RequestHeader("Authorization") String token
  ) {
    return queries.payouts(user(token));
  }

  private long user(String token) {
    try {
      return auth.authenticateBearer(token).userId();
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
