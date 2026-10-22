package com.surprising.gateway.provider.controller;

import com.surprising.account.api.model.*;
import com.surprising.account.provider.service.AccountService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.product.GatewayProductSelection;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 资产只读入口：先核对本人和产品归属；未启用产品为空，已启用产品沿用实时查询。 */
@RestController
@RequestMapping("/api/v1/gateway/account")
public class AccountAssetsController {
    private final AuthService auth;
    private final GatewayProductSelection selection;
    private final GatewayProductServices products;

    public AccountAssetsController(AuthService auth, GatewayProductSelection selection, GatewayProductServices products) {
        this.auth = auth;
        this.selection = selection;
        this.products = products;
    }

    @GetMapping("/balances")
    public ResponseEntity<BalanceQueryResponse> balances(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestParam long userId, HttpServletRequest request) {
        ProductLine product = product(authorization, userId, request);
        var balance = products.disabled().contains(product) ? new BalanceQueryResponse(0, List.of())
                : products.service(product, AccountService.class).balances(userId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(balance);
    }

    @GetMapping("/product-balances")
    public ResponseEntity<ProductBalanceQueryResponse> productBalances(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam long userId, @RequestParam AccountType accountType, HttpServletRequest request) {
        ProductLine product = product(authorization, userId, request);
        if (accountType.productLine().orElse(null) != product)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "account type does not match product");
        var balance = products.disabled().contains(product) ? new ProductBalanceQueryResponse(0, List.of())
                : products.service(product, AccountService.class).productBalances(userId, accountType);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(balance);
    }

    private ProductLine product(String authorization, long userId, HttpServletRequest request) {
        if (authorization == null || authorization.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bearer token required");
        final long owner;
        try { owner = auth.authenticateBearer(authorization).userId(); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage()); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage()); }
        if (userId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be positive");
        if (owner != userId) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "userId does not match authenticated user");
        ProductLine product = selection.resolve(request, null, false);
        if (product == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "productLine is required");
        return product;
    }
}
