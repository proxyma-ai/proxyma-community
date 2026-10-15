package ai.proxyma.core.diagnostics.daily;

import ai.proxyma.core.common.diagnostics.Diagnostics;
import ai.proxyma.core.common.diagnostics.DiagnosticsLogAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Attaches {@link DiagnosticsLogAppender} to the {@code ai.proxyma} logger, in both editions,
 * without either edition's logging configuration having to know.
 *
 * <p>On the {@code ai.proxyma} logger rather than the root: the appender only wants Proxyma's own
 * loggers, and attaching there means a third-party logger's event never reaches it at all. Spring
 * Boot configures Logback before any bean exists and does not reinitialize it afterwards, so an
 * appender added here stays attached for the life of the context. Warnings logged during startup,
 * before this bean, are not counted.
 */
@Component
public class DiagnosticsLogCapture {

    private DiagnosticsLogAppender appender;

    @PostConstruct
    public void attach() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) return;
        Logger logger = context.getLogger("ai.proxyma");
        if (logger.getAppender(DiagnosticsLogAppender.NAME) != null) return;
        DiagnosticsLogAppender a = new DiagnosticsLogAppender(Diagnostics.aggregator());
        a.setContext(context);
        a.start();
        logger.addAppender(a);
        appender = a;
    }

    @PreDestroy
    public void detach() {
        if (appender == null) return;
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger("ai.proxyma").detachAppender(appender);
        }
        appender.stop();
        appender = null;
    }
}
