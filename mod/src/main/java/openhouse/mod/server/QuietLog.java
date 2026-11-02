package openhouse.mod.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;

final class QuietLog extends AbstractFilter {
    static final String FLUSH = "IO Exception while flushing Data Output Stream!";
    private static boolean installed;

    private QuietLog() { super(Filter.Result.NEUTRAL, Filter.Result.NEUTRAL); }

    @Override
    public Result filter(LogEvent e) {

        if (e.getThrown() instanceof java.io.IOException && FLUSH.equals(e.getMessage().getFormattedMessage())) return Result.DENY;
        return Result.NEUTRAL;
    }

    static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            QuietLog f = new QuietLog();
            f.start();

            ctx.getConfiguration().getRootLogger().addFilter(f);
            ctx.updateLoggers();
        } catch (Throwable t) {
            ServerRoom.LOG.debug("couldn't quiet BTA's disconnect noise: {}", t.toString());
        }
    }
}
