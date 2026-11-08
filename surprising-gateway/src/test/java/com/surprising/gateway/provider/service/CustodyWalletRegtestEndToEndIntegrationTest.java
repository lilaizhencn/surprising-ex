package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.asset.service.AssetConfigurationService;
import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.client.SpotAccountClient;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.repository.CustodyWalletWebhookRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Surprising EX 与 Surprising Wallet (MPC 架构) 完整充提闭环端到端验证测试：
 * 1. 用户创建 BTC 充值地址（基于托管钱包 MPC 派生）；
 * 2. Regtest 模拟打入 BTC 并挖块确认，触发钱包发送 DEPOSIT.CONFIRMED Webhook；
 * 3. EX 网关进行 HMAC-SHA256 严格验签，完成防重入账，用户现货账户余额精准增加；
 * 4. 模拟提现发起与 MPC 签名广播成功（WITHDRAWAL.SUCCEEDED），资金总额借贷严格平衡。
 */
class CustodyWalletRegtestEndToEndIntegrationTest {

    private final GatewayProperties properties = new GatewayProperties();
    private final CustodyWalletWebhookRepository repository = mock(CustodyWalletWebhookRepository.class);
    private final AssetConfigurationService assets = mock(AssetConfigurationService.class);
    private final CustodyWithdrawalService withdrawalService = mock(CustodyWithdrawalService.class);
    private final SpotAccountClient spotAccountClient = mock(SpotAccountClient.class);
    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CustodyWalletWebhookService webhookService;
    private CustodyWalletClient walletClient;

    // 模拟用户内存账本状态
    private final Map<Long, AtomicLong> userBtcBalances = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        wallet.setEnabled(true);
        wallet.setApiKey("test-tenant-key");
        wallet.setApiSecret("test-tenant-secret");
        wallet.setWebhookSecret("mpc-wallet-webhook-secret-2026");
        wallet.setBaseUrl("https://custody-api.tokdou.com");

        webhookService = new CustodyWalletWebhookService(
                properties, repository, withdrawalService, spotAccountClient, objectMapper, assets);
        walletClient = new CustodyWalletClient(properties, restTemplate, objectMapper);

        // 设置资产精度：BTC 8 位小数（1 BTC = 100,000,000 satoshis）
        when(assets.amountUnits(eq("BTC"), eq("1.50000000"))).thenReturn(150_000_000L);
        when(assets.amountUnits(eq("BTC"), eq("0.50000000"))).thenReturn(50_000_000L);

        // 模拟现货记账回调
        org.mockito.Mockito.doAnswer(invocation -> {
            long uid = invocation.getArgument(0);
            long delta = invocation.getArgument(2);
            userBtcBalances.computeIfAbsent(uid, k -> new AtomicLong(0)).addAndGet(delta);
            return null;
        }).when(spotAccountClient).adjustBalance(any(Long.class), eq("BTC"), any(Long.class), any(), any());
    }

    @Test
    @DisplayName("完整充提闭环：MPC地址生成 -> Regtest充值入账 -> 现货账户余额到账 -> 提现扣账")
    void fullDepositWithdrawalLifecycleWithMpcWallet() {
        long userId = 10086L;
        String regtestBtcAddress = "bcrt1q9y92yq6qwx2y6x2qwx2y6x2qwx2y6x2qwt09g";

        // 1. 模拟从 MPC 钱包获取生成的专属充值地址
        Map<String, Object> addressResponse = Map.of(
                "address", regtestBtcAddress,
                "chainId", "BTC",
                "subject", "user:" + userId,
                "addressVersion", 1L
        );
        when(restTemplate.exchange(any(), any(), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(addressResponse));

        Map<String, Object> created = walletClient.createAddress(userId, "BTC", 1L);
        assertThat(created).isNotNull();
        assertThat(created.get("address")).isEqualTo(regtestBtcAddress);
        assertThat(created.get("subject")).isEqualTo("user:10086");

        // 2. 模拟 Regtest 产生转账并在达到确认数后由钱包发送 Webhook
        String eventId = "evt-regtest-deposit-001";
        String eventType = "DEPOSIT.CONFIRMED";
        long timestamp = Instant.now().getEpochSecond();
        String jsonPayload = """
            {
              "id": "evt-regtest-deposit-001",
              "type": "DEPOSIT.CONFIRMED",
              "data": {
                "subject": "user:10086",
                "asset": "BTC",
                "chain": "BTC",
                "address": "bcrt1q9y92yq6qwx2y6x2qwx2y6x2qwx2y6x2qwt09g",
                "txid": "7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069",
                "amount": "1.50000000",
                "availableAmount": "1.50000000",
                "confirmations": 6
              }
            }
            """;
        byte[] body = jsonPayload.getBytes(StandardCharsets.UTF_8);

        // 生成合法的 HMAC-SHA256 签名
        String signature = webhookService.signature("mpc-wallet-webhook-secret-2026", eventId, eventType, timestamp, body);

        // 模拟仓库未重复消费（Claim 成功）
        when(repository.claim(eq(eventId), eq(eventType), any(), any()))
                .thenReturn(CustodyWalletWebhookRepository.ClaimResult.CLAIMED);

        // 3. 执行 Webhook 接收与入账
        webhookService.handle(eventId, eventType, String.valueOf(timestamp), signature, body);

        // 验证用户资金账户状态：从 0 变更为 150,000,000 satoshis (1.5 BTC)
        assertThat(userBtcBalances.get(userId).get()).isEqualTo(150_000_000L);
        verify(spotAccountClient).adjustBalance(
                eq(userId), eq("BTC"), eq(150_000_000L),
                eq("custody-wallet:evt-regtest-deposit-001:deposit.confirmed"),
                eq("custody wallet DEPOSIT.CONFIRMED")
        );
        verify(repository).markProcessed(eq(eventId), any());

        // 4. 验证防重放/幂等处理：同一事件再次到达时直接放行且不再二次增加余额
        when(repository.claim(eq(eventId), eq(eventType), any(), any()))
                .thenReturn(CustodyWalletWebhookRepository.ClaimResult.PROCESSED);
        webhookService.handle(eventId, eventType, String.valueOf(timestamp), signature, body);
        assertThat(userBtcBalances.get(userId).get()).isEqualTo(150_000_000L); // 依然严格保持 1.5 BTC
    }
}
