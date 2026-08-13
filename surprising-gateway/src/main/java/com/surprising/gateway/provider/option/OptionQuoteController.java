package com.surprising.gateway.provider.option;

import com.surprising.gateway.provider.option.OptionQuoteModels.OptionQuoteResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/options")
public class OptionQuoteController {

    private final OptionQuoteService service;

    public OptionQuoteController(OptionQuoteService service) {
        this.service = service;
    }

    @GetMapping("/quote")
    public OptionQuoteResponse quote(@RequestParam @NotBlank String symbol) {
        try {
            return service.quote(symbol);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (OptionQuoteService.OptionQuoteUnavailableException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }
}
