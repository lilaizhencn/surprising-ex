package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.repository.UserDepositAddressRecord;
import com.surprising.gateway.provider.repository.UserDepositAddressRepository;

import com.surprising.asset.model.AssetConfiguration.*;
import com.surprising.asset.repository.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalletFundingServiceTest {
    private final AssetRepository assets = mock();
    private final AssetNetworkRepository networks = mock();
    private final CustodyWalletClient custody = mock();
    private final UserDepositAddressRepository addressRepository = mock();
    private final WalletFundingService service = new WalletFundingService(assets, networks, custody);
    private final WalletFundingService cachingService = new WalletFundingService(assets, networks, custody, addressRepository);
    private Asset asset() { return new Asset(1, "USDT", "Tether", "/assets/usdt.svg", 100000000, true, true, 1, Instant.now()); }
    private Network network(boolean deposit) { return new Network(1, 1, "ETH", "Ethereum", "0x123", false, 6, deposit, true,
        BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, 12, 1, Instant.now()); }
    private Map<String, Object> chain(boolean scan) { return Map.of("chain", "ETH", "status", "ACTIVE", "enabled", true,
        "scanEnabled", scan, "withdrawalEnabled", true, "assetSymbols", List.of("ETH", "USDT"), "family", "evm"); }
    private void setup() {
        when(assets.byAccountingCode("USDT")).thenReturn(Optional.of(asset()));
        when(networks.list(1)).thenReturn(List.of(network(true)));
        when(custody.chains()).thenReturn(List.of(chain(true)));
        when(custody.subject(42)).thenReturn("user:42");
    }
    @Test void displayReadsAreSharedAcrossOperationsAndExpireButAddressChecksStayFresh() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong();
        var cached = new WalletFundingService(assets, networks, custody, addressRepository, now::get);
        when(assets.list(false)).thenReturn(List.of(asset()));
        when(networks.enabled()).thenReturn(List.of(network(true)));
        when(custody.chains()).thenReturn(List.of(chain(true)));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<List<WalletFundingService.FundingAsset>>>();
            for (int i=0;i<16;i++) futures.add(executor.submit(() -> cached.catalog("deposit")));
            for (var future : futures) assertThat(future.get()).hasSize(1);
        }
        assertThat(cached.catalog("withdraw")).hasSize(1);
        verify(assets).list(false); verify(networks).enabled(); verify(custody).chains();
        when(assets.byAccountingCode("USDT")).thenReturn(Optional.of(asset()));
        when(networks.list(1)).thenReturn(List.of(network(false)));
        assertThatThrownBy(() -> cached.depositAddress(42, "USDT", "ETH")).isInstanceOf(IllegalArgumentException.class);
        verify(custody, never()).createAddress(anyLong(), anyString(), any());
        now.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
        when(custody.chains()).thenReturn(List.of(chain(false)));
        assertThat(cached.catalog("deposit")).isEmpty();
        verify(assets, times(2)).list(false);
    }
    @Test void failedCustodyRefreshDoesNotPublishUsableOrCachedFakeNetworks() {
        var now = new java.util.concurrent.atomic.AtomicLong();
        var cached = new WalletFundingService(assets, networks, custody, addressRepository, now::get);
        when(assets.list(false)).thenReturn(List.of(asset())); when(networks.enabled()).thenReturn(List.of(network(true)));
        when(custody.chains()).thenThrow(new IllegalStateException("offline")).thenReturn(List.of(chain(true)));
        assertThatThrownBy(() -> cached.catalog("deposit")).hasMessage("offline");
        assertThat(cached.catalog("deposit")).hasSize(1);
        verify(custody, times(2)).chains();
    }

    @Test void catalogIntersectsAdminConfigurationAndCustodyCapabilities() {
        when(assets.list(false)).thenReturn(List.of(asset()));
        when(networks.enabled()).thenReturn(List.of(network(true)));
        when(custody.chains()).thenReturn(List.of(chain(false)));
        assertThat(service.catalog("deposit")).isEmpty();
        assertThat(service.catalog("withdraw")).hasSize(1);
        assertThatThrownBy(() -> service.catalog("unsupported")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void withdrawalCatalogUsesFundingFlagsIndependentlyOfTradingAndKeepsJsonContract() throws Exception {
        var listed = new Asset(1, "USDT", "Tether", "", 100000000, true, false, 1, Instant.now());
        when(assets.list(false)).thenReturn(List.of(listed));
        when(networks.enabled()).thenReturn(List.of(network(true)));
        when(custody.chains()).thenReturn(List.of(chain(true)));
        var catalog = service.catalog("withdraw");
        assertThat(catalog).hasSize(1);
        var json = new tools.jackson.databind.ObjectMapper().valueToTree(catalog);
        assertThat(json.at("/0/networks/0/networkCode").asText()).isEqualTo("ETH");
        assertThat(json.at("/0/networks/0/family").asText()).isEqualTo("evm");
        assertThat(json.at("/0/networks/0/chainDecimals").asInt()).isEqualTo(6);
        assertThat(json.at("/0/networks/0/minWithdrawal").asText()).isEqualTo("10");
        assertThat(json.at("/0/networks/0").has("configuration")).isFalse();
    }
    @Test void missingCustodyFamilyCannotPublishAnUnvalidatedWithdrawalNetwork() {
        when(assets.list(false)).thenReturn(List.of(asset()));
        when(networks.enabled()).thenReturn(List.of(network(true)));
        var incomplete = new HashMap<>(chain(true)); incomplete.remove("family");
        when(custody.chains()).thenReturn(List.of(incomplete));
        assertThatThrownBy(() -> service.catalog("withdraw"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("network family");
    }
    @Test void disabledOrWrongAssetNetworkCannotAllocateAnAddress() {
        setup();when(networks.list(1)).thenReturn(List.of(network(false)));
        assertThatThrownBy(() -> service.depositAddress(42,"USDT","ETH")).isInstanceOf(IllegalArgumentException.class);
        verify(custody,never()).createAddress(anyLong(),anyString(),any());
        verify(custody,never()).chains();
    }
    @Test void unsupportedCustodyRouteCannotAllocateAnAddress() {
        setup();when(custody.chains()).thenReturn(List.of(chain(false)));
        assertThatThrownBy(() -> service.depositAddress(42,"USDT","ETH")).isInstanceOf(IllegalStateException.class);
        verify(custody,never()).createAddress(anyLong(),anyString(),any());
    }
    @Test void usesStableUserAndVersionForAtomicCustodyGetOrCreate() {
        setup();when(custody.createAddress(42,"ETH",1L)).thenReturn(Map.of("address","0xabc","subject","user:42","chain","ETH","status","ACTIVE","memo","123"));
        var first=service.depositAddress(42,"USDT","ETH");
        assertThat(service.depositAddress(42,"USDT","ETH")).isEqualTo(first);
        assertThat(first.memo()).isEqualTo("123");
        verify(custody,times(2)).createAddress(42,"ETH",1L);
    }
    @Test void rejectsAddressBelongingToAnotherUser() {
        setup();when(custody.createAddress(42,"ETH",1L)).thenReturn(Map.of("address","0xabc","subject","user:43","chain","ETH","status","ACTIVE"));
        assertThatThrownBy(() -> service.depositAddress(42,"USDT","ETH")).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsInactiveOrMismatchedChainAddress() {
        setup();when(custody.createAddress(42,"ETH",1L)).thenReturn(Map.of("address","0xabc","subject","user:42","chain","TRON","status","ACTIVE"));
        assertThatThrownBy(() -> service.depositAddress(42,"USDT","ETH")).isInstanceOf(IllegalStateException.class);
        when(custody.createAddress(42,"ETH",1L)).thenReturn(Map.of("address","0xabc","subject","user:42","chain","ETH","status","DISABLED"));
        assertThatThrownBy(() -> service.depositAddress(42,"USDT","ETH")).isInstanceOf(IllegalStateException.class);
    }
    @Test void returnsCachedAddressLocallyWithoutCallingCustody() {
        setup();
        when(addressRepository.find(42, "ETH", 1L)).thenReturn(Optional.of(
                new UserDepositAddressRecord(1L, 42L, "ETH", "evm", "0xlocal", "tag1", 1L, Instant.now(), Instant.now())));
        var result = cachingService.depositAddress(42, "USDT", "ETH");
        assertThat(result.address()).isEqualTo("0xlocal");
        assertThat(result.memo()).isEqualTo("tag1");
        verify(custody, never()).createAddress(anyLong(), anyString(), any());
    }
    @Test void allocatesAndPersistsLocallyWhenNotCached() {
        setup();
        when(addressRepository.find(42, "ETH", 1L)).thenReturn(Optional.empty());
        when(custody.createAddress(42, "ETH", 1L)).thenReturn(
                Map.of("address", "0xabc", "subject", "user:42", "chain", "ETH", "status", "ACTIVE", "memo", "memo1"));
        var result = cachingService.depositAddress(42, "USDT", "ETH");
        assertThat(result.address()).isEqualTo("0xabc");
        assertThat(result.memo()).isEqualTo("memo1");
        verify(custody, times(1)).createAddress(42, "ETH", 1L);
        verify(addressRepository, times(1)).save(42L, "ETH", "evm", "0xabc", "memo1", 1L);
    }
}
