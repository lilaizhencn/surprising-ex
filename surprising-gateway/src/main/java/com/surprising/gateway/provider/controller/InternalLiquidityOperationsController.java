package com.surprising.gateway.provider.controller;

import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.gateway.provider.service.LiquidityOperationsService;
import com.surprising.gateway.provider.model.LiquidityOperationMetadata;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Host-local authenticated operations using the same funds, leverage and instrument services as admin. */
@RestController
@RequestMapping("/internal/v1/operations/liquidity")
public class InternalLiquidityOperationsController {
    private final String token;
    private final LiquidityOperationsService operations;
    private final ObjectMapper mapper;

    public InternalLiquidityOperationsController(@Value("${surprising.gateway.operations.token:}") String token,
            LiquidityOperationsService operations,ObjectMapper mapper) {
        this.token=token;
        this.operations=operations;
        this.mapper=mapper;
    }

    @PostMapping("/balance-adjustments")
    public Object adjust(@Valid @RequestBody ProductBalanceAdjustmentRequest body, HttpServletRequest request) {
        authorize(request);
        return operations.adjust(body,metadata(request,body,body.referenceId(),body.reason()));
    }

    @PostMapping("/leverage")
    public Object leverage(@Valid @RequestBody LeverageSettingRequest body, HttpServletRequest request) {
        authorize(request);
        return operations.leverage(body,metadata(request,body,request.getHeader("X-Operation-Id"),body.reason()));
    }

    @GetMapping("/leverage")
    public Object leverage(@RequestParam long userId, @RequestParam String instrumentId,
            @RequestParam ProductLine productLine, @RequestParam com.surprising.trading.api.model.MarginMode marginMode,
            HttpServletRequest request) {
        authorize(request);
        if (userId <= 0 || productLine == null || marginMode == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"user, product and margin mode are required");
        try { com.surprising.product.api.utils.InstrumentIds.parse(instrumentId); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,invalid.getMessage(),invalid); }
        return operations.leverage(userId,instrumentId,productLine,marginMode);
    }

    @PostMapping("/instruments")
    public Object instrument(@Valid @RequestBody InstrumentUpsertRequest body,
                             @RequestParam String reason, HttpServletRequest request) {
        authorize(request);
        try { com.surprising.instrument.provider.utils.InstrumentConfigurationFormats.validate(body); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,invalid.getMessage(),invalid); }
        return operations.instrument(body,metadata(request,body,request.getHeader("X-Operation-Id"),reason));
    }

    private void authorize(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        boolean loopback = "127.0.0.1".equals(remote) || "::1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote);
        String supplied = request.getHeader("X-Operations-Token");
        if (token.length() < 32 || supplied == null || !loopback
                || request.getHeader("Forwarded") != null || request.getHeader("X-Forwarded-For") != null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "host-local operations authentication required");
    }

    private LiquidityOperationMetadata metadata(HttpServletRequest request,Object body,String reference,String reason) {
        if (reference == null || reference.isBlank() || reference.length() > 128
                || reason == null || reason.isBlank() || reason.length() > 128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"operation reference and reason are required (max 128)");
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)));
            return new LiquidityOperationMetadata(reference,reason,request.getRequestURI(),request.getRemoteAddr(),hash);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
