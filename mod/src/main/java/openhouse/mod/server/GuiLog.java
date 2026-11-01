package openhouse.mod.server;

import java.util.concurrent.BlockingQueue;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.filter.ThresholdFilter;

final class GuiLog extends AbstractAppender {
    static final String QUEUE = "ServerGuiConsole";
    static final int CAP = 20000;
    static volatile boolean enabled = true;
    private static volatile boolean installed;
    private final BlockingQueue<String> queue = com.mojang.logging.LogQueues.getOrCreateQueue(QUEUE);

    private GuiLog() {
        super("OpenHostServerGui", ThresholdFilter.createFilter(Level.INFO, Filter.Result.ACCEPT, Filter.Result.DENY), null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent e) {
        if (!enabled || queue.size() >= CAP) return;
        StringBuilder b = new StringBuilder();
        b.append(String.format("[%tT %s]: ", e.getTimeMillis(), e.getLevel())).append(e.getMessage().getFormattedMessage()).append('\n');
        Throwable t = e.getThrown();
        if (t != null) {
            java.io.StringWriter w = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(w));
            b.append(w);
        }
        queue.offer(b.toString());
    }

    static void installIfWanted() {
        if ("classic".equals(System.getProperty("openhouse.openhost.gui"))) installNow();
    }

    static synchronized void installNow() {
        if (installed) return;
        installed = true;
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration cfg = ctx.getConfiguration();
            if (cfg.getAppenders().containsKey(QUEUE)) return;
            GuiLog a = new GuiLog();
            a.start();
            cfg.addAppender(a);
            cfg.getRootLogger().addAppender(a, null, null);
            ctx.updateLoggers();
        } catch (Throwable t) {

            ServerRoom.LOG.warn("couldn't show the log in the server window: {}", t.toString());
        }
    }
}
