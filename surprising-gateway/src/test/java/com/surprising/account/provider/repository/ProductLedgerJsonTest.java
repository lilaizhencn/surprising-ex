package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.*;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductLedgerEntryResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProductLedgerJsonTest {
    @Test void cashAndIdentifiersRemainExactPastTheJavascriptIntegerLimit() {
        var row = new ProductLedgerEntryResponse(9_007_199_254_740_993L, 9_007_199_254_740_995L,
                AccountType.USDT_DELIVERY, "USDT", -9_007_199_254_740_997L, 9_007_199_254_740_999L,
                "DELIVERY_SETTLEMENT", "LINEAR_DELIVERY:73:1700000000000", "交割结算", "73", Instant.EPOCH);
        var encoded = JsonMapper.builder().findAndAddModules().build().writeValueAsString(row);
        assertThat(encoded).contains("\"entryId\":\"9007199254740993\"", "\"userId\":\"9007199254740995\"",
                "\"amountUnits\":\"-9007199254740997\"", "\"balanceAfterUnits\":\"9007199254740999\"", "\"instrumentId\":\"73\"");
    }
}
