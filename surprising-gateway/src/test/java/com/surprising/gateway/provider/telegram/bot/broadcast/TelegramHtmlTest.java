package com.surprising.gateway.provider.telegram.bot.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class TelegramHtmlTest {

    @Test
    void keepsTelegramFormatting() {
        assertEquals("Hello <b>world</b>", TelegramHtml.toTelegram("<p>Hello <strong>world</strong></p>"));
        assertEquals("<a href=\"https://example.com\">site</a>",
                TelegramHtml.toTelegram("<a href=\"https://example.com\" onclick=\"x()\">site</a>"));
        assertEquals("a\nb", TelegramHtml.toTelegram("<p>a</p><p>b</p>"));
    }

    @Test
    void dropsScriptsAndUnsafeLinks() {
        assertEquals("Hi", TelegramHtml.toTelegram("<script>alert(1)</script>Hi"));
        assertEquals("x", TelegramHtml.toTelegram("<a href=\"javascript:alert(1)\">x</a>"));
        assertEquals("pic", TelegramHtml.toTelegram("pic<img src=\"x\" onerror=\"alert(1)\">"));
        assertFalse(TelegramHtml.toTelegram("<b>1 < 2</b>").contains("< 2"));
        assertEquals("<b>1 &lt; 2</b>", TelegramHtml.toTelegram("<b>1 < 2</b>"));
    }
}
