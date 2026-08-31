package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class CoreResponseEncodingBenchmark {

    @Benchmark
    public int encodeCommittedResponse(ResponseState state) {
        return CoreMessageCodec.encodeResponse(
                state.header, state.response, state.committedCoreSequence, state.destination);
    }

    @State(Scope.Thread)
    public static class ResponseState {
        @Param({"0", "4096"})
        public int dataBytes;

        private CoreMessageHeader header;
        private CoreResponse response;
        private byte[] destination;
        private long committedCoreSequence;

        @Setup(Level.Trial)
        public void setUp() {
            CoreMessageHeader command = CoreMessageHeader.command(
                    CoreMessageType.PLACE_ORDER, UUID.fromString("a42fd764-9430-4f41-93ab-50a559f4b7b2"),
                    ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY,
                    7, 11, 101, 1_700_000_000_000L, 19);
            header = command.response(CoreMessageType.COMMAND_RESULT);
            byte[] data = new byte[dataBytes];
            for (int index = 0; index < data.length; index++) data[index] = (byte) index;
            response = new CoreResponse(ResponseStatus.OK, ResponseStatus.APPLIED, CoreResultCode.NONE,
                    41, 43, 47, data);
            destination = new byte[CoreMessageCodec.encodedResponseLength(response)];
            committedCoreSequence = 53;
        }
    }
}
