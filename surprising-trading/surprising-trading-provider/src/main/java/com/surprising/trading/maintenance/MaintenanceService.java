package com.surprising.trading.maintenance;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.*;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.service.StableOrderIdentity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Low-rate administrative coordinator. No database or network work executes on a Core owner lane. */
@Service
public class MaintenanceService {
    private final ProductLine line;
    private final MaintenanceRepository repository;
    private final MaintenanceAeronGateway core;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public MaintenanceService(TradingOrderProperties properties, MaintenanceRepository repository,
            MaintenanceAeronGateway core, ObjectMapper json,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.line = properties.getKafka().getProductLine(); this.repository = repository; this.core = core;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ProductLine productLine() { return line; }
    public MaintenanceTask create(String admin, MaintenanceRequest request) {
        request.validate(line);
        // Verify Core availability and the symbol before accepting a durable task.
        core.maintenance(request.symbol(),0,1);
        return transactions.execute(status -> repository.create(line,admin,request));
    }
    public MaintenanceTask get(long id) { return repository.get(line,id,false); }
    public List<MaintenanceTask> list(long before) { if (before < 0) throw new IllegalArgumentException("invalid cursor"); return repository.list(line,before); }
    public List<MaintenanceRepository.Action> actions(long id, String after) { return repository.actions(get(id),after); }

    public MaintenanceTask retry(long id) {
        return transactions.execute(status -> {
            var task = repository.get(line,id,true);
            if (!task.status().equals("BLOCKED")) throw new IllegalArgumentException("only blocked tasks can be retried");
            boolean newRound = task.phase().equals("VERIFY");
            String phase=switch(task.phase()) { case "GATE_REJECTED" -> "GATE"; case "RELEASE_REJECTED" -> "RELEASE"; default -> task.phase(); };
            repository.update(task,"RUNNING",newRound ? "TRIGGERS" : phase,
                    newRound ? 0 : task.cursorUserId(),newRound ? Math.incrementExact(task.roundNo()) : task.roundNo(),null);
            return get(id);
        });
    }

    public MaintenanceTask release(long id) {
        return transactions.execute(status -> {
            var task = repository.get(line,id,true);
            if (task.status().equals("RELEASED")) return task;
            if (task.status().equals("BLOCKED") && task.phase().equals("GATE_REJECTED")) {
                repository.update(task,"RELEASED","GATE_REJECTED",task.cursorUserId(),task.roundNo(),null);
                return get(id);
            }
            if (task.request().mode() == MaintenanceRequest.Mode.SETTLEMENT) throw new IllegalArgumentException("fixed-price clearance is irreversible and cannot resume trading");
            if (!task.status().equals("COMPLETED") && !task.status().equals("BLOCKED")) throw new IllegalArgumentException("wait for completion or a blocked task before releasing maintenance");
            if (task.phase().equals("GATE")) throw new IllegalArgumentException("retry and reconcile the maintenance gate before releasing it");
            // Never resume trading while an unknown close command can still execute.
            if (repository.pendingAction(task) != null) throw new IllegalArgumentException("retry and reconcile the pending command before releasing maintenance");
            repository.update(task,"RUNNING","RELEASE",task.cursorUserId(),task.roundNo(),null);
            return get(id);
        });
    }

    public record Position(String userId, String symbol, String marginMode, String positionSide,
                           String signedQuantitySteps, String entryPriceTicks, String positionMarginUnits) { }
    public record Preview(ProductLine productLine, String symbol, String gateMode, String gateTaskId,
                          String instrumentVersion, List<Position> positions, List<String> orderIds,
                          List<String> triggerOrderIds, boolean moreUsers, String nextUserId,
                          boolean moreOrders, boolean moreTriggers) { }
    public Preview preview(String symbol, long userId, long afterUserId) {
        if (userId < 0 || afterUserId < 0) throw new IllegalArgumentException("invalid user cursor");
        var page = core.maintenance(symbol,afterUserId,8);
        var positions = new ArrayList<Position>();
        for (long owner : userId == 0 ? page.userIds() : List.of(userId)) {
            for (var p : positions(owner,symbol)) positions.add(new Position(Long.toString(owner),p.symbol(),p.marginMode().name(),p.positionSide().name(),
                    Long.toString(p.signedQuantitySteps()),Long.toString(p.entryPriceTicks()),Long.toString(p.positionMarginUnits())));
        }
        var open = core.openOrders(userId,symbol,0,21);
        var pending = core.openTriggers(userId,symbol,0,21);
        return new Preview(line,symbol,page.state().mode().name(),Long.toString(page.state().taskId()),Long.toString(page.instrumentVersion()),
                List.copyOf(positions),open.stream().limit(20).map(v -> Long.toString(v.orderId())).toList(),
                pending.stream().limit(20).map(v -> Long.toString(v.triggerOrderId())).toList(),userId == 0 && page.hasMore(),
                page.userIds().isEmpty() ? "0" : Long.toString(page.userIds().getLast()),open.size()>20,pending.size()>20);
    }

    /** One task and at most one mutating Core command per invocation; SQL lock coordinates provider replicas. */
    public void tick() {
        transactions.executeWithoutResult(status -> {
            var task = repository.next(line);
            if (task == null) return;
            try { step(task); }
            catch (RuntimeException failure) {
                repository.block(task,failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()));
            }
        });
    }

    public record PlannedAction(String kind, long userId, long entityId, PlaceOrderCommand order,
                                SettleInstrumentCommand settlement) { }

    private void step(MaintenanceTask task) {
        var pending = repository.pendingAction(task);
        if (pending != null) { execute(task,pending); return; }
        String symbol = task.request().symbol();
        switch (task.phase()) {
            case "GATE" -> {
                CoreInstrumentMaintenance.Mode mode = switch (task.request().mode()) {
                    case CANCEL -> CoreInstrumentMaintenance.Mode.HALTED;
                    case MARKET,LIMIT -> CoreInstrumentMaintenance.Mode.REDUCE_ONLY;
                    case SETTLEMENT -> CoreInstrumentMaintenance.Mode.SETTLEMENT;
                };
                updateGate(task,new CoreMaintenanceCodec.Command(symbol,0,
                        new CoreInstrumentMaintenance(task.taskId(),mode,mode == CoreInstrumentMaintenance.Mode.SETTLEMENT ? task.priceTicks() : 0)),false);
            }
            case "RELEASE" -> updateGate(task,new CoreMaintenanceCodec.Command(symbol,task.taskId(),CoreInstrumentMaintenance.TRADING),true);
            case "TRIGGERS" -> {
                var values = core.openTriggers(task.userId(),symbol,0,1);
                if (values.isEmpty()) { repository.phase(task,"ORDERS"); return; }
                var trigger = values.getFirst();
                plan(task,"trigger:"+trigger.triggerOrderId(),new PlannedAction("TRIGGER",trigger.userId(),trigger.triggerOrderId(),null,null));
            }
            case "ORDERS" -> {
                var values = core.openOrders(task.userId(),symbol,0,1);
                if (values.isEmpty()) {
                    repository.phase(task,switch(task.request().mode()) { case CANCEL -> "VERIFY"; case MARKET,LIMIT -> "CLOSE"; case SETTLEMENT -> "SETTLE"; });
                    return;
                }
                var order = values.getFirst();
                plan(task,"cancel:"+order.orderId(),new PlannedAction("CANCEL",order.userId(),order.orderId(),null,null));
            }
            case "CLOSE" -> planNextUser(task);
            case "SETTLE" -> {
                var progress = core.settlementProgress(symbol);
                if (progress.settlementId() == task.taskId() && progress.complete()) { repository.phase(task,"VERIFY"); return; }
                if (progress.settlementId() != 0 && progress.settlementId() != task.taskId() && !progress.complete()) throw new IllegalStateException("another settlement is in progress");
                var page = core.maintenance(symbol,0,1);
                var command = new SettleInstrumentCommand(task.taskId(),symbol,page.instrumentVersion(),task.priceTicks(),0,
                        progress.settlementId() == task.taskId() ? progress.nextCursorUserId() : 0,16,
                        progress.settlementId() == task.taskId() ? progress.nextCursorOrderId() : 0,20);
                plan(task,"settle:"+task.step(),new PlannedAction("SETTLE",0,0,null,command));
            }
            case "VERIFY" -> verify(task);
            default -> throw new IllegalStateException("unknown maintenance phase: "+task.phase());
        }
    }

    private void planNextUser(MaintenanceTask task) {
        long owner;
        if (task.userId() != 0) {
            if (task.cursorUserId() != 0) { repository.phase(task,"VERIFY"); return; }
            owner = task.userId();
        } else {
            var page = core.maintenance(task.request().symbol(),task.cursorUserId(),1);
            if (page.userIds().isEmpty()) { repository.phase(task,"VERIFY"); return; }
            owner = page.userIds().getFirst();
        }
        for (var position : positions(owner,task.request().symbol())) {
            String key = "close:"+task.roundNo()+":"+owner+":"+position.marginMode()+":"+position.positionSide();
            String clientId = "maint-"+stable(task,key);
            var order = new PlaceOrderCommand(StableOrderIdentity.orderId(line,owner,clientId),task.request().symbol(),
                    position.instrumentVersion(),position.signedQuantitySteps() > 0 ? CoreOrderSide.SELL : CoreOrderSide.BUY,
                    task.priceTicks(),Math.absExact(position.signedQuantitySteps()),true,position.marginMode(),position.positionSide(),
                    task.request().mode() == MaintenanceRequest.Mode.LIMIT ? CoreOrderType.LIMIT : CoreOrderType.MARKET,
                    CoreTimeInForce.IOC,false,clientId);
            plan(task,key,new PlannedAction("CLOSE",owner,0,order,null));
        }
        repository.update(task,"RUNNING","CLOSE",owner,task.roundNo(),null);
    }

    private void execute(MaintenanceTask task, MaintenanceRepository.Action stored) {
        var action = json.readValue(stored.requestJson(),PlannedAction.class);
        Object result;
        switch (action.kind()) {
            case "CANCEL" -> {
                var current = core.orderState(action.userId(),action.entityId());
                if (current != null && current.remainingQuantitySteps() > 0 && current.status().equals("OPEN")) {
                    var response = core.command(CoreMessageType.CANCEL_ORDER,stable(task,stored.key()),action.userId(),TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(action.entityId())));
                    requireTerminal(response);
                    result = CoreCommandResultCodec.decode(response.data());
                } else result = current == null ? java.util.Map.of("state","NOT_ACTIVE","orderId",Long.toString(action.entityId())) : current;
            }
            case "TRIGGER" -> {
                var current = core.triggerState(action.userId(),action.entityId());
                if (current != null && (current.status() == CoreTriggerOrderStatus.PENDING || current.status() == CoreTriggerOrderStatus.TRIGGERING)) {
                    var response = core.command(CoreMessageType.CANCEL_TRIGGER_ORDER,stable(task,stored.key()),action.userId(),CoreTriggerOrderCodec.encodeId(action.entityId()));
                    requireTerminal(response);
                    result = java.util.Map.of("commandStatus",response.commandStatus().name(),"code",response.resultCode().name(),
                            "triggerOrderId",Long.toString(action.entityId()),"commandId",stable(task,stored.key()).toString());
                } else result = current == null ? java.util.Map.of("state","NOT_ACTIVE","triggerOrderId",Long.toString(action.entityId())) : current;
            }
            case "CLOSE" -> {
                var outcome = core.commandOutcome(CoreMessageType.PLACE_ORDER,stable(task,stored.key()),action.userId(),
                        TradingCommandCodec.encodePlaceOrder(action.order()));
                if (!(outcome instanceof com.surprising.aeron.client.CoreCommandOutcome.Terminal terminal)
                        || terminal.response().resultCode() == CoreResultCode.MATCHING_PENDING) {
                    throw new IllegalStateException("close outcome is not terminal; retry preserves the exact command");
                }
                var response = terminal.response();
                result = java.util.Map.of("status",response.commandStatus().name(),"code",response.resultCode().name(),
                        "data",response.data().length == 0 ? "" : CoreCommandResultCodec.decode(response.data()));
            }
            case "SETTLE" -> {
                var response = core.command(CoreMessageType.SETTLE_INSTRUMENT,stable(task,stored.key()),0,TradingCommandCodec.encodeSettleInstrument(action.settlement()));
                requireTerminal(response);
                var progress = CoreSettlementProgressCodec.decode(response.data());
                repository.completeAction(task,stored,json.writeValueAsString(progress));
                repository.phase(task,task.phase());
                if (progress.requiredInsuranceUnits() > 0) repository.block(task,"BLOCKED_INSURANCE: requiredInsuranceUnits="+progress.requiredInsuranceUnits());
                return;
            }
            default -> throw new IllegalStateException("unknown maintenance action");
        }
        repository.completeAction(task,stored,json.writeValueAsString(result));
    }

    private void verify(MaintenanceTask task) {
        var state = preview(task.request().symbol(),task.userId(),0);
        if (!state.orderIds().isEmpty() || !state.triggerOrderIds().isEmpty()) {
            repository.block(task,"Residual orders remain; retry cancellation before completion"); return;
        }
        if (task.request().mode() != MaintenanceRequest.Mode.CANCEL
                && (!state.positions().isEmpty() || state.moreUsers())) {
            repository.block(task,"BLOCKED_LIQUIDITY: positions remain; inspect the executed orders and retry or release maintenance"); return;
        }
        if (task.request().mode() == MaintenanceRequest.Mode.SETTLEMENT) {
            var progress = core.settlementProgress(task.request().symbol());
            if (!progress.complete() || progress.settlementId() != task.taskId()) throw new IllegalStateException("Core settlement is not complete");
            core.command(CoreMessageType.UPDATE_INSTRUMENT_MAINTENANCE,stable(task,"closed"),0,
                    CoreMaintenanceCodec.encodeCommand(new CoreMaintenanceCodec.Command(task.request().symbol(),task.taskId(),
                            new CoreInstrumentMaintenance(task.taskId(),CoreInstrumentMaintenance.Mode.CLOSED,task.priceTicks()))));
        }
        repository.update(task,"COMPLETED","VERIFY",task.cursorUserId(),task.roundNo(),null);
    }
    private List<CorePositionView> positions(long userId, String symbol) {
        var user = core.userState(userId);
        return user == null ? List.of() : user.positions().stream().filter(p -> p.symbol().equals(symbol) && p.signedQuantitySteps()!=0).toList();
    }
    private void plan(MaintenanceTask task, String key, PlannedAction action) { repository.addAction(task,key,json.writeValueAsString(action)); }
    private void updateGate(MaintenanceTask task,CoreMaintenanceCodec.Command command,boolean release) {
        var outcome=core.commandOutcome(CoreMessageType.UPDATE_INSTRUMENT_MAINTENANCE,
                stable(task,(release?"release:":"gate:")+task.roundNo()),0,CoreMaintenanceCodec.encodeCommand(command));
        if (!(outcome instanceof com.surprising.aeron.client.CoreCommandOutcome.Terminal terminal)) {
            throw new IllegalStateException("maintenance gate outcome is unknown; retry preserves its identity");
        }
        if (terminal.response().commandStatus()!=ResponseStatus.APPLIED) {
            // A known rejection must get a new identity on retry; an unknown outcome must not.
            repository.update(task,"BLOCKED",release ? "RELEASE_REJECTED" : "GATE_REJECTED",task.cursorUserId(),Math.incrementExact(task.roundNo()),
                    terminal.response().resultCode()+": Core rejected the maintenance gate transition");
            return;
        }
        if (release) repository.update(task,"RELEASED","RELEASE",task.cursorUserId(),task.roundNo(),null);
        else repository.phase(task,"TRIGGERS");
    }
    private static void requireTerminal(CoreResponse response) {
        if (response.resultCode() == CoreResultCode.MATCHING_PENDING) throw new IllegalStateException("Core command is still pending; retry preserves its identity");
    }
    private static UUID stable(MaintenanceTask task, String key) {
        return UUID.nameUUIDFromBytes(("MAINTENANCE:"+task.productLine()+":"+task.id()+":"+key).getBytes(StandardCharsets.UTF_8));
    }
}
