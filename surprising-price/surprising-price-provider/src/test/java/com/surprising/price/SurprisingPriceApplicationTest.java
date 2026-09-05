package com.surprising.price;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.price.consumer.MarkPriceConsumerProperties;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.mark.config.MarkPriceProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SurprisingPriceApplicationTest {

    @Test
    void logsTheEffectiveMarkAndWebSocketMatrixConfiguration(CapturedOutput output) {
        IndexPriceProperties index = new IndexPriceProperties();
        index.getCalculation().setPollDelayMs(250L);
        index.getCalculation().setMinValidSources(4);
        index.getCalculation().setMaxSourceAge(Duration.ofSeconds(3));
        index.getWebSocket().setIdleTimeout(Duration.ofSeconds(9));
        index.getWebSocket().setReconnectInitialDelay(Duration.ofMillis(500));
        index.getWebSocket().setReconnectMaxDelay(Duration.ofSeconds(6));
        index.getWebSocket().setHealthCheckInterval(Duration.ofSeconds(2));
        MarkPriceProperties mark = new MarkPriceProperties();
        mark.getCalculation().setPublishIntervalMs(250L);

        new SurprisingPriceApplication(index, mark, new MarkPriceConsumerProperties())
                .validateProductLineAlignment();

        assertThat(output).contains(
                "markPublishIntervalMs=250",
                "indexPollDelayMs=250",
                "indexMinValidSources=4",
                "indexMaxSourceAge=PT3S",
                "indexWebSocketIdleTimeout=PT9S",
                "indexWebSocketReconnectInitialDelay=PT0.5S",
                "indexWebSocketReconnectMaxDelay=PT6S",
                "indexWebSocketHealthCheckInterval=PT2S");
    }
}
