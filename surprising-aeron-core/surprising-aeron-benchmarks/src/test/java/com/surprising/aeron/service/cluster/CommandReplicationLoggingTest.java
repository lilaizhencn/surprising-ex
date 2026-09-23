package com.surprising.aeron.service.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeron.exceptions.AeronException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CommandReplicationLoggingTest {
    @Test
    void retainsWarningAndFatalStackTracesAndOnlyTerminatesOnFatalError() {
        Logger logger = (Logger) LoggerFactory.getLogger(CommandReplicationNode.class);
        var output = new ListAppender<ILoggingEvent>();
        output.start();
        logger.addAppender(output);
        AtomicInteger exitCode = new AtomicInteger();
        var handler = CommandReplicationNode.errorHandler("archive", exitCode::set);
        try {
            handler.onError(new AeronException("temporary warning", AeronException.Category.WARN));
            assertThat(exitCode.get()).isZero();
            handler.onError(new AeronException("fatal failure", AeronException.Category.FATAL));
            assertThat(exitCode.get()).isEqualTo(1);
            assertThat(output.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.ERROR);
            assertThat(output.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("Aeron archive");
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
            });
        } finally {
            logger.detachAppender(output);
            output.stop();
        }
    }
}
