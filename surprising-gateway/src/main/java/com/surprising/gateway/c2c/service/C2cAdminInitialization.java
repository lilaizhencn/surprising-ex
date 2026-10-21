package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.model.C2cAdminRequest;

import com.surprising.gateway.c2c.exception.C2cProblem;

import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.account.provider.exception.AccountCommandRejectedException;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 首次开放前以零金额建立内部 Core 账户；审批/审计先提交，Core I/O 不占用数据库事务。 */
@Service
public class C2cAdminInitialization {
    private record Accepted(JwtPrincipal administrator, long escrow, long fees, String asset) { }
    private final C2cAdminAccess access;
    private final C2cMarketService markets;
    private final GatewayProductServices products;
    private final TransactionTemplate transactions;

    public C2cAdminInitialization(C2cAdminAccess access, C2cMarketService markets, GatewayProductServices products,
                                 PlatformTransactionManager transactions) {
        this.access=access;this.markets=markets;this.products=products;
        this.transactions=new TransactionTemplate(transactions);
    }

    public Map<String,Object> initialize(String authorization, C2cAdminRequest request, byte[] body) {
        Accepted accepted=transactions.execute(transaction -> {
            var administrator=access.write(authorization,request,body);
            products.requireEnabled(ProductLine.SPOT);
            var configuration=markets.configuration(true);
            var configured=markets.markets(false);
            if(configured.isEmpty()) throw C2cProblem.conflict("请先配置至少一个 C2C 市场");
            // 请求不接受金额、账户或资产；命令按账户及资产固定身份，重复调用仍为同一笔零金额初始化。
            String asset=configured.getFirst().asset();
            access.audit(administrator,request,body);
            return new Accepted(administrator,configuration.escrowUserId(),configuration.feeUserId(),asset);
        });
        var commands=products.service(ProductLine.SPOT,AccountCommandGateway.class);
        var escrow=initialize(commands,accepted,accepted.escrow(),"ESCROW");
        var fees=initialize(commands,accepted,accepted.fees(),"FEES");
        return Map.of("accounts",List.of(escrow,fees),"asset",accepted.asset(),
                "commandsApplied","APPLIED".equals(escrow.get("status")) && "APPLIED".equals(fees.get("status")),
                "message","请从资金核对查询确认 realtime 已同步；初始化不增加资金，也不自动开放市场");
    }

    private Map<String,Object> initialize(AccountCommandGateway commands, Accepted accepted, long userId, String purpose) {
        var command=new BalanceAdjustmentRequest(userId,accepted.asset(),0,
                "c2c-initialize-v1-"+userId+"-"+accepted.asset(),"C2C internal account initialization with zero balance");
        try {
            commands.adjustBalance(command,Long.toString(accepted.administrator().userId()),accepted.administrator().username());
            return Map.of("purpose",purpose,"status","APPLIED");
        } catch(AccountCommandRejectedException | IllegalArgumentException rejected) {
            return Map.of("purpose",purpose,"status","REJECTED","message","Core 未接受账户初始化，请检查账户及资产配置");
        } catch(RuntimeException unknown) {
            return Map.of("purpose",purpose,"status","UNKNOWN","message","原初始化结果待确认；请查询资金核对，重试仍沿用原命令身份");
        }
    }
}
