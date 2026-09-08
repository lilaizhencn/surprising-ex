package com.surprising.aeron.tools.instrument;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.CoreInstrumentState;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_SEED_TEST_JDBC_URL",matches=".+")
class InstrumentSeedCoreContractTest {
    @Test void everyRetainedSeedCanBeEncodedAndValidatedByItsOwnProductCore() throws Exception {
        var load=ClusterInstrumentSeedMain.class.getDeclaredMethod("load",String.class,String.class,String.class,ProductLine.class);
        load.setAccessible(true);
        int total=0;
        for(var line:ProductLine.values()) {
            var rows=(List<?>)load.invoke(null,System.getenv("INSTRUMENT_SEED_TEST_JDBC_URL"),"maintenance","",line);
            assertThat(rows.size()).isBetween(1,512);
            for(var row:rows) {
                var accessor=row.getClass().getDeclaredMethod("command"); accessor.setAccessible(true);
                var command=(UpsertInstrumentCommand)accessor.invoke(row);
                var decoded=TradingCommandCodec.decodeUpsertInstrument(TradingCommandCodec.encodeUpsertInstrument(command));
                var state=CoreInstrumentState.from(line,decoded);
                assertThat(state.contractType().productLine()).isEqualTo(line);
                assertThat(state.baseAsset()).isNotEqualTo(state.quoteAsset());
                assertThat(state.lastChangeId()).isGreaterThanOrEqualTo(state.changeId());
                total++;
            }
        }
        assertThat(total).isEqualTo(1151);
    }
}
