package com.surprising.asset.service;

import com.surprising.asset.model.AssetConfiguration.*;
import com.surprising.asset.repository.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WithdrawalQuoteTest {
    private final AssetRepository assets=mock(AssetRepository.class);
    private final AssetNetworkRepository networks=mock(AssetNetworkRepository.class);
    private final AssetConfigurationService service=new AssetConfigurationService(assets,networks,mock(AssetConfigurationChangeRepository.class),new tools.jackson.databind.ObjectMapper());
    @BeforeEach void setup() {
        when(assets.byAccountingCode("USDT")).thenReturn(Optional.of(new Asset(1,"USDT","Tether","",1_000_000,true,true,0,Instant.now())));
        route(true,"2.5");
    }
    private void route(boolean enabled,String fee) {
        when(networks.list(1)).thenReturn(List.of(new Network(1,1,"ETH","Ethereum","test-token",false,6,true,enabled,
                BigDecimal.ONE,BigDecimal.TEN,new BigDecimal(fee),12,7,Instant.now())));
    }
    @Test void inclusiveFeeAndNetUseExactAccountingUnitsAndTheConfirmedRevision() {
        var quote=service.withdrawalQuote("USDT","ETH","25.000000");
        assertThat(quote.amount()).isEqualTo("25");assertThat(quote.amountUnits()).isEqualTo(25_000_000);
        assertThat(quote.fee()).isEqualTo("2.5");assertThat(quote.feeUnits()).isEqualTo(2_500_000);
        assertThat(quote.receivedAmount()).isEqualTo("22.5");assertThat(quote.receivedUnits()).isEqualTo(22_500_000);
        assertThat(quote.networkRevision()).isEqualTo(7);
    }
    @Test void disabledNetworkMinimumPrecisionAndOverflowAreEnforcedOnTheServer() {
        for(String amount:List.of("9.999999","25.0000001","9223372036854.775808","-25","NaN")) {
            assertThatThrownBy(()->service.withdrawalQuote("USDT","ETH",amount)).isInstanceOf(IllegalArgumentException.class);
        }
        route(false,"2.5");
        assertThatThrownBy(()->service.withdrawalQuote("USDT","ETH","25")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("disabled");
    }
    @Test void aZeroFeeIsSupportedWithoutInventingAnyNetworkFee() {
        route(true,"0");var quote=service.withdrawalQuote("USDT","ETH","25");
        assertThat(quote.feeUnits()).isZero();assertThat(quote.receivedAmount()).isEqualTo("25");
    }
}
