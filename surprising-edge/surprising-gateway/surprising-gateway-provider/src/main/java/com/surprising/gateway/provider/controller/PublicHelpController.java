package com.surprising.gateway.provider.controller;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/help")
public class PublicHelpController {

    private static final Instant CONTENT_UPDATED_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final List<HelpArticle> ARTICLES = List.of(
            new HelpArticle("account-security", "Account security basics", "SECURITY",
                    "Protect login access before moving funds or trading.",
                    "Use a unique password, enable an authenticator, and review high-risk verification scenes before using funding features."),
            new HelpArticle("trading-orders", "How spot orders work", "TRADING",
                    "Understand limit and market order submission.",
                    "A limit order waits at the requested price. A market order executes against available liquidity and may have price impact. The final state always comes from the order service."),
            new HelpArticle("funding-deposit", "Deposit network checklist", "FUNDING",
                    "Choose the correct asset and network before generating an address.",
                    "Only send the selected asset through the selected network. Check Memo or Tag requirements returned by the custody wallet service."),
            new HelpArticle("funding-withdrawal", "Withdrawal review checklist", "FUNDING",
                    "Why withdrawals may require extra verification.",
                    "Withdrawals can require verified KYC, email and authenticator verification, risk checks, and a configured custody route. A pending response must not be retried blindly."),
            new HelpArticle("derivatives-risk", "Derivatives risk controls", "DERIVATIVES",
                    "Margin, leverage and liquidation are account-level risk decisions.",
                    "Leverage magnifies both gains and losses. Mark price, maintenance margin, funding and liquidation rules are controlled by the derivatives and risk services."),
            new HelpArticle("support-contact", "Contact support", "SUPPORT",
                    "How to report an account or transaction issue.",
                    "Include the affected product, request identifier, approximate time and a description. Never include a password, private key, API secret or authenticator seed."));

    @GetMapping("/articles")
    public List<HelpArticle> articles(@RequestParam(value = "query", required = false) String query,
                                      @RequestParam(value = "category", required = false) String category) {
        String normalizedQuery = normalize(query);
        String normalizedCategory = normalize(category);
        return ARTICLES.stream()
                .filter(article -> normalizedCategory.isBlank()
                        || article.category().equalsIgnoreCase(normalizedCategory))
                .filter(article -> normalizedQuery.isBlank() || Stream.of(
                                article.title(), article.summary(), article.body(), article.category())
                        .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(normalizedQuery)))
                .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record HelpArticle(String articleId,
                               String title,
                               String category,
                               String summary,
                               String body,
                               Instant updatedAt) {
        public HelpArticle(String articleId, String title, String category, String summary, String body) {
            this(articleId, title, category, summary, body, CONTENT_UPDATED_AT);
        }
    }
}
