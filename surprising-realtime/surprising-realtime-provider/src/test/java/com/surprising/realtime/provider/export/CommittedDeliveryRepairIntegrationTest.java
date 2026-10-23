package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.export.config.TradeExportProperties;
import com.surprising.realtime.provider.export.service.CommittedOrderProjectionService;
import io.aeron.*;
import io.aeron.archive.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.codecs.*;
import io.aeron.cluster.service.ClusterCounters;
import io.aeron.driver.*;
import java.net.DatagramSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommittedDeliveryRepairIntegrationTest {
    @TempDir Path temp;
    private long sequence;
    private final ProductLine line = ProductLine.LINEAR_DELIVERY;
    private static final long TIME=1_700_000_000_000L;

    @Test void archiveDeliveryPagesStopAtCommitAndSqlFailureCannotAdvanceCheckpoint() throws Exception {
        var repository=mock(CommittedOrderProjectionService.class);
        String directory=temp.resolve("driver").toString();
        int port;try(var socket=new DatagramSocket(0)){port=socket.getLocalPort();}
        String control="aeron:udp?endpoint=127.0.0.1:"+port;
        Path cluster=temp.resolve("cluster"), checkpoint=temp.resolve("history.delivery-repair");Files.createDirectories(cluster);
        var config=new TradeExportProperties(cluster,directory,control,checkpoint,0,null,null,null);
        var exporter=new CommittedTradeExporter(config,line,null,repository);
        try(var driver=MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(directory)
                    .dirDeleteOnShutdown(true).threadingMode(ThreadingMode.SHARED));
            var service=Archive.launch(new Archive.Context().aeronDirectoryName(directory).archiveDir(temp.resolve("archive").toFile())
                    .controlChannel(control).replicationChannel("aeron:udp?endpoint=127.0.0.1:0").threadingMode(ArchiveThreadingMode.SHARED));
            var aeron=Aeron.connect(new Aeron.Context().aeronDirectoryName(directory));
            var archive=AeronArchive.connect(new AeronArchive.Context().aeron(aeron).ownsAeronClient(false)
                    .controlRequestChannel(control).controlResponseChannel("aeron:ipc"));
            var commit=ClusterCounters.allocate(aeron,new UnsafeBuffer(new byte[1024]),"test commit",
                    AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID,0)) {
            archive.startRecording("aeron:ipc",1001,SourceLocation.LOCAL);
            try(var pub=aeron.addExclusivePublication("aeron:ipc?mtu=128",1001)) {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);int counter;
                while((counter=RecordingPos.findCounterIdBySession(aeron.countersReader(),pub.sessionId()))<0){check(deadline);Thread.sleep(1);}
                long recording=RecordingPos.getRecordingId(aeron.countersReader(),counter);
                try(var log=new RecordingLog(cluster.toFile(),true)){log.appendTerm(recording,1,0,TIME);log.force(2);}
                offer(pub,CoreMessageType.REGISTER_INSTRUMENT,0,TradingCommandCodec.encodeRegisterInstrument(
                        new RegisterInstrumentCommand("1",ContractType.LINEAR_DELIVERY.ordinal(),"BTC","USDT","USDT",1,1,1,100_000,50_000,0,0,TIME+10_000,-1,0)));
                offer(pub,CoreMessageType.APPLY_MARK_PRICE,0,TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1",100,1,TIME)));
                long before=0;
                for(long user=1;user<=2;user++) {
                    offer(pub,CoreMessageType.ADJUST_BALANCE,user,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",100_000)));
                    before=offer(pub,CoreMessageType.PLACE_ORDER,user,TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100+user,"1",
                            user==1?CoreOrderSide.SELL:CoreOrderSide.BUY,100,10,false,CoreMarginMode.CROSS,CorePositionSide.NET,
                            CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"delivery-"+user)));
                }
                long first=offer(pub,CoreMessageType.SETTLE_INSTRUMENT,0,TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(900,
                        "1", 110, 0, 1, 0, 16)));
                long last=offer(pub,CoreMessageType.SETTLE_INSTRUMENT,0,TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(900,
                        "1", 110, 1, 1, 0, 16)));
                while(aeron.countersReader().getCounterValue(counter)<last){check(deadline);Thread.sleep(1);}
                commit.set(before);exporter.repairDelivery(before);
                byte[] seed=Files.readAllBytes(checkpoint);
                verifyNoInteractions(repository); // No Kafka client and no order/watermark writes in this mode.
                // Fail one projection delivery, then allow the retry. Replay must start at the old durable state.
                doThrow(new IllegalStateException("SQL unavailable")).doNothing().when(repository).persistDelivery(any());
                commit.set(first);
                assertThatThrownBy(()->exporter.repairDelivery(first)).hasMessage("SQL unavailable");
                assertThat(Files.readAllBytes(checkpoint)).isEqualTo(seed);
                exporter.repairDelivery(first);
                assertThat(TradeExportCheckpoint.read(checkpoint,line).logPosition()).isEqualTo(first);
                var pages=org.mockito.ArgumentCaptor.forClass(com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedDeliveryPage.class);
                verify(repository,times(2)).persistDelivery(pages.capture());
                assertThat(pages.getValue().cashFlows().getFirst().amountUnits()).isEqualTo(-100);
                assertThat(pages.getValue().cashFlows().getFirst().userId()).isEqualTo(1);
                commit.set(last);
                exporter.repairDelivery(last);
                verify(repository,times(3)).persistDelivery(pages.capture());
                assertThat(pages.getValue().cashFlows().getFirst().amountUnits()).isEqualTo(100);
                assertThat(pages.getValue().cashFlows().getFirst().userId()).isEqualTo(2);
                assertThat(TradeExportCheckpoint.read(checkpoint,line).logPosition()).isEqualTo(last);
                verify(repository,never()).persist(any(),anyList(),anyList(),anyLong(),anyList());
                verify(repository,never()).repairCommandResults(any(),anyList());
                verify(repository,never()).persistFunding(any());
                Path live = temp.resolve("live.bin"), copied = temp.resolve("copied.delivery-repair");
                TradeExportCheckpoint.read(checkpoint,line).write(live);
                Files.copy(live,copied);
                var renamed = new TradeExportProperties(cluster,directory,control,copied,0,null,null,null);
                assertThatThrownBy(()->new CommittedTradeExporter(renamed,line,null,repository).repairDelivery(last))
                        .hasMessageContaining("checkpoint purpose");
                assertThatThrownBy(()->exporter.repairDelivery(Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
                var unsafe=new TradeExportProperties(cluster,directory,control,temp.resolve("checkpoint.bin"),0,null,null,null);
                assertThatThrownBy(()->new CommittedTradeExporter(unsafe,line,null,repository).repairDelivery(last)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }
    private long offer(ExclusivePublication pub,CoreMessageType type,long user,byte[] body) throws Exception {
        long seq=++sequence;
        long timestamp = type == CoreMessageType.SETTLE_INSTRUMENT ? TIME+20_000 : TIME;
        var command=new CoreMessage(CoreMessageHeader.command(type,new UUID(91,seq),line,CommandSource.OPERATIONS,871,seq,user,timestamp,seq),body);
        byte[] encoded=CoreMessageCodec.encode(command);
        int offset=MessageHeaderEncoder.ENCODED_LENGTH+SessionMessageHeaderEncoder.BLOCK_LENGTH;
        var buffer=new UnsafeBuffer(new byte[offset+encoded.length]);
        new SessionMessageHeaderEncoder().wrapAndApplyHeader(buffer,0,new MessageHeaderEncoder()).leadershipTermId(1).clusterSessionId(7).timestamp(timestamp);
        buffer.putBytes(offset,encoded);long result,deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while((result=pub.offer(buffer))<0){check(deadline);Thread.sleep(1);}return result;
    }
    private static void check(long deadline){if(System.nanoTime()>deadline)throw new AssertionError("archive fixture timed out");}
}
