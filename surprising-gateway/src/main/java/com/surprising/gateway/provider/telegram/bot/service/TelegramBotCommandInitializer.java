package com.surprising.gateway.provider.telegram.bot.service;

import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient;
import com.surprising.gateway.provider.telegram.bot.client.TelegramBotClient.BotCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TelegramBotCommandInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotCommandInitializer.class);

    private final TelegramBotClient botClient;

    public TelegramBotCommandInitializer(TelegramBotClient botClient) {
        this.botClient = botClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!botClient.isConfigured()) {
            log.debug("Telegram Bot token not configured, skipping native command registration.");
            return;
        }

        try {
            // 1. 默认与英文 (English)
            List<BotCommand> enCommands = List.of(
                    new BotCommand("start", "Open trading terminal & dashboard"),
                    new BotCommand("assets", "View multi-account balances & equity"),
                    new BotCommand("market", "Realtime quotes & orderbook depth"),
                    new BotCommand("positions", "Manage active derivative contracts"),
                    new BotCommand("orders", "View open orders and fast cancel"),
                    new BotCommand("trade", "Fast trade on spot and perpetual"),
                    new BotCommand("deposit", "Deposit crypto (TRC20, ERC20, BTC)"),
                    new BotCommand("withdraw", "Withdraw crypto with security PIN"),
                    new BotCommand("transfer", "Transfer funds between accounts"),
                    new BotCommand("settings", "Security verification and language")
            );
            botClient.setMyCommands(enCommands, null);
            botClient.setMyCommands(enCommands, "en");

            // 2. 简体中文 (Chinese)
            List<BotCommand> zhCommands = List.of(
                    new BotCommand("start", "打开交易终端与主控制台"),
                    new BotCommand("assets", "查看多账户资产与实时估值"),
                    new BotCommand("market", "查看实时盘口与深度行情"),
                    new BotCommand("positions", "管理合约持仓与一键平仓"),
                    new BotCommand("orders", "查看活跃挂单与撤单"),
                    new BotCommand("trade", "快捷现货与合约交易"),
                    new BotCommand("deposit", "快速充值 (TRC20, ERC20, BTC)"),
                    new BotCommand("withdraw", "安全链上提币"),
                    new BotCommand("transfer", "资金划转 (现货/合约)"),
                    new BotCommand("settings", "安全确认级别与多语言设置")
            );
            botClient.setMyCommands(zhCommands, "zh");

            // 3. 俄语 (Russian)
            List<BotCommand> ruCommands = List.of(
                    new BotCommand("start", "Торговый терминал и главное меню"),
                    new BotCommand("assets", "Балансы активов и оценка"),
                    new BotCommand("market", "Рыночные котировки и книга ордеров"),
                    new BotCommand("positions", "Управление позициями деривативов"),
                    new BotCommand("orders", "Активные ордера и быстрая отмена"),
                    new BotCommand("trade", "Быстрая торговля (Спот/Фьючерсы)"),
                    new BotCommand("deposit", "Ввод средств (TRC20, ERC20, BTC)"),
                    new BotCommand("withdraw", "Вывод средств с подтверждением"),
                    new BotCommand("transfer", "Перевод между счетами платформы"),
                    new BotCommand("settings", "Безопасность и настройки языка")
            );
            botClient.setMyCommands(ruCommands, "ru");

            log.info("✓ Telegram native bot commands registered successfully for EN, ZH, and RU.");
        } catch (Exception ex) {
            log.warn("Failed to register Telegram bot commands: {}", ex.getMessage());
        }
    }
}
