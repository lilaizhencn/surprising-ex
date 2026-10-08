package com.surprising.gateway.provider.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.account.api.model.*;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.*;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.gateway.provider.service.*;
import com.surprising.product.api.ProductLine;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 六个真实 Core 状态机、真实账户方法和划转编排；仅把 Aeron 网络边界替换为同步协议调用。 */
class MultiProductMoneyIntegrationTest {
    @Test void oneGatewayKeepsSixBalancesIsolatedAndCrossProductRetriesConserveFunds() {
        var products = mock(GatewayProductServices.class);
        when(products.enabled()).thenReturn(List.of(ProductLine.values()));
        var cores = new EnumMap<ProductLine, Core>(ProductLine.class);
        try {
            for (var line : ProductLine.values()) {
                var core = new Core(line); cores.put(line,core);
                when(products.service(line, AccountCommandGateway.class)).thenReturn(core.accounts);
                core.accounts.adjustBalance(new BalanceAdjustmentRequest(42,"USDT",100_000L*(line.ordinal()+1),"same-deposit-reference","test deposit"),null,null);
                // 同一业务请求重试，不能重复入账。
                core.accounts.adjustBalance(new BalanceAdjustmentRequest(42,"USDT",100_000L*(line.ordinal()+1),"same-deposit-reference","test deposit"),null,null);
                assertThat(core.balance()).isEqualTo(100_000L*(line.ordinal()+1));
            }
            long initial = total(cores);
            var transfers = new ProductTransferCoordinator(new ProductAccountAccess(products));
            for (var line : ProductLine.values()) {
                if (line == ProductLine.SPOT) continue;
                var command = transfer(ProductLine.SPOT,line,"allocation-"+line);
                assertThat(transfers.transfer(command).status()).isEqualTo(ProductTransferStatus.COMPLETED);
                assertThat(transfers.transfer(command).status()).isEqualTo(ProductTransferStatus.COMPLETED);
                assertThat(total(cores)).isEqualTo(initial);
                assertThat(transfers.transfer(transfer(line,ProductLine.SPOT,"return-"+line)).status()).isEqualTo(ProductTransferStatus.COMPLETED);
                assertThat(total(cores)).isEqualTo(initial);
            }
            var spot = cores.get(ProductLine.SPOT);
            var perpetual = cores.get(ProductLine.LINEAR_PERPETUAL);
            spot.dropReply.set(true);
            var lostSourceReply = transfer(ProductLine.SPOT,ProductLine.LINEAR_PERPETUAL,"source-reply-lost");
            assertThat(transfers.transfer(lostSourceReply).status()).isEqualTo(ProductTransferStatus.PENDING);
            assertThat(spot.accounts.pendingTransfers(10)).hasSize(1);
            // 提交结果丢失，源 Core 恢复后通过原来的划转标识重放。
            spot.restart();
            transfers.reconcile(100);
            assertThat(spot.accounts.pendingTransfers(10)).isEmpty();
            assertThat(total(cores)).isEqualTo(initial);
            perpetual.dropReply.set(true);
            var lostTargetReply = transfer(ProductLine.SPOT,ProductLine.LINEAR_PERPETUAL,"target-reply-lost");
            assertThat(transfers.transfer(lostTargetReply).status()).isEqualTo(ProductTransferStatus.SOURCE_DEBITED);
            long targetAfterCommit = perpetual.balance();
            perpetual.restart();
            transfers.reconcile(100);
            assertThat(perpetual.balance()).isEqualTo(targetAfterCommit);
            assertThat(total(cores)).isEqualTo(initial);
            var custody = new SpotAccountClient(products);
            long derivativeBefore = perpetual.balance();
            custody.adjustBalance(42,"USDT",-500,"withdrawal-one","withdrawal");
            custody.adjustBalance(42,"USDT",-500,"withdrawal-one","withdrawal");
            assertThat(perpetual.balance()).isEqualTo(derivativeBefore);
            assertThat(total(cores)).isEqualTo(initial-500);
            custody.adjustBalance(42,"USDT",500,"withdrawal-one-refund","refund");
            assertThat(total(cores)).isEqualTo(initial);
            var excessive = new ProductTransferCommand(42,"overspend","SPOT","USDT_PERPETUAL","USDT",Long.MAX_VALUE,"overspend","test");
            assertThat(transfers.transfer(excessive).status()).isEqualTo(ProductTransferStatus.FAILED);
            assertThat(total(cores)).isEqualTo(initial);
        } finally { cores.values().forEach(Core::close); }
    }
    private ProductTransferCommand transfer(ProductLine source, ProductLine target, String key) {
        return new ProductTransferCommand(42,key,source.accountTypeCode(),target.accountTypeCode(),"USDT",1000,key,"allocation");
    }
    private long total(Map<ProductLine,Core> cores) { return cores.values().stream().mapToLong(Core::balance).sum(); }
    private static final class Core implements AutoCloseable {
        final ProductLine line;
        TradingCoreRuntime state;
        long sequence;
        final AtomicBoolean dropReply = new AtomicBoolean();
        final AccountCommandGateway accounts;
        Core(ProductLine line) {
            this.line=line;state=new TradingCoreRuntime(line);
            var properties=new AccountProperties();properties.getKafka().setProductLine(line);
            var aeron=mock(AccountAeronGateway.class);
            when(aeron.command(any(),any(),anyLong(),any())).thenAnswer(call -> {
                long seq=++sequence;
                var header=CoreMessageHeader.command(call.getArgument(0),call.getArgument(1),line,CommandSource.GATEWAY,998,seq,call.getArgument(2),1_700_000_000_000L+seq,seq);
                var response=state.apply(new CoreMessage(header,call.getArgument(3)));
                if (response.commandStatus()!=ResponseStatus.APPLIED)
                    throw new AccountCommandRejectedException(response.resultCode().name(),response.resultCode().name());
                if (dropReply.compareAndSet(true,false)) throw new IllegalStateException("committed reply lost");
                return response;
            });
            when(aeron.query(any(),any(),any())).thenAnswer(call -> query(call.getArgument(0),0,call.getArgument(2)));
            when(aeron.userState(anyLong())).thenAnswer(call -> {
                var response=query(CoreMessageType.USER_STATE_QUERY,call.getArgument(0),new byte[0]);
                return response.status()==ResponseStatus.OK?CoreStateQueryCodec.decodeUserState(response.data()):null;
            });
            accounts=new AccountCommandGateway(properties,aeron);
        }
        CoreResponse query(CoreMessageType type,long user,byte[] payload) {
            long seq=++sequence;
            return state.apply(new CoreMessage(CoreMessageHeader.query(type,UUID.randomUUID(),line,CommandSource.GATEWAY,998,seq,user,1_700_000_000_000L+seq,seq),payload));
        }
        long balance() { var user=state.tradingState().users().get(42L);return user==null?0:user.totalUnits("USDT"); }
        void restart() { byte[] snapshot=state.snapshot(900);state.close();state=TradingCoreRuntime.fromSnapshot(line,snapshot); }
        @Override public void close() { state.close(); }
    }
}
