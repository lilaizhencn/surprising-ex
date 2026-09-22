package com.surprising.trading.maintenance;

import com.surprising.product.api.ProductLine;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Uses the existing trading-orders gateway permission, approval and audit route. */
@RestController
@RequestMapping("/api/v1/admin/trading/orders/maintenance")
public class AdminMaintenanceController {
    private final MaintenanceService service;
    public AdminMaintenanceController(MaintenanceService service) { this.service = service; }

    @ModelAttribute
    public void authorize(@RequestHeader(value="X-Admin-User-Id",required=false) String admin,
            @RequestHeader(value="X-Product-Line",required=false) String header,
            @RequestParam ProductLine productLine) {
        if (admin == null || !admin.matches("[1-9][0-9]{0,18}")) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"trusted admin identity is required");
        if (productLine != service.productLine() || header == null || !header.equals(productLine.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"explicit matching product line is required");
        }
    }
    @GetMapping
    public List<MaintenanceTask> list(@RequestParam(defaultValue="0") long beforeId) { return service.list(beforeId); }
    @GetMapping("/preview")
    public MaintenanceService.Preview preview(@RequestParam String symbol,@RequestParam(defaultValue="0") long userId,
            @RequestParam(defaultValue="0") long afterUserId) { return service.preview(symbol,userId,afterUserId); }
    @GetMapping("/{id}") public MaintenanceTask get(@PathVariable long id) { return service.get(id); }
    @GetMapping("/{id}/actions") public List<MaintenanceRepository.Action> actions(@PathVariable long id,
            @RequestParam(defaultValue="") String afterKey) { return service.actions(id,afterKey); }
    @PostMapping public MaintenanceTask create(@RequestHeader("X-Admin-User-Id") String admin,
            @RequestBody MaintenanceRequest request) { return service.create(admin,request); }
    @PostMapping("/{id}/retry") public MaintenanceTask retry(@PathVariable long id) { return service.retry(id); }
    @PostMapping("/{id}/release") public MaintenanceTask release(@PathVariable long id) { return service.release(id); }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String,String> invalid(IllegalArgumentException e) { return java.util.Map.of("message",e.getMessage()); }
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public java.util.Map<String,String> conflict() { return java.util.Map.of("message","This symbol already has an active maintenance task"); }
}
