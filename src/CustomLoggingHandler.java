import java.util.logging.Handler;
import java.util.logging.Formatter;
import java.util.logging.Logger;
import java.util.logging.LogRecord;

public class CustomLoggingHandler extends Handler {
    static public void prepareLogger(Logger logger) {
        logger.setUseParentHandlers(false);
        for (Handler h : logger.getHandlers()) {
            logger.removeHandler(h);
        }
        logger.addHandler(new CustomLoggingHandler());
    }

    public CustomLoggingHandler() {
        Formatter formatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                String out = "";
                // may be useful for checking logging levels:
                // out += record.getLevel() + ": ";
                out += record.getMessage();
                return out;
            }
        };
        this.setFormatter(formatter);
    }

    @Override
    public void publish(LogRecord record) {
        Formatter formatter = getFormatter();
        System.out.println(formatter.format(record));
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() throws SecurityException {
    }
}

