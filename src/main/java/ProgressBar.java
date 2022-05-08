import java.util.Timer;
import java.util.TimerTask;

public class ProgressBar extends Timer {
    private static ProgressBar progressBar;
    private String message;
    private StringBuilder bar;
    private boolean printTimer;
    private int period;

    private ProgressBar() {
        super(true);
        initBar("Starting block scanning");
        period = 43200000;
        super.schedule(new TimerTask() {
            @Override
            public void run() {
                int lastIndex = bar.lastIndexOf("#");
                if (lastIndex < 19)
                    bar.replace( lastIndex + 2, lastIndex + 3, "#");
                else bar = new StringBuilder("[ | | | | | | | | | ]");
                print("\r");
            }
        },0,250);
    }

    private void initBar(String message) {
        this.message = message;
        if (message.equals("over")) {
            printTimer = true;
            this.message = "Waiting for the next block scan to start";
        } else {
            printTimer = false;
        }
        bar = new StringBuilder("[ | | | | | | | | | ]");
    }

    public static void run() {
        if (progressBar == null) progressBar = new ProgressBar();
    }

    public static void printMessage(String... message) {
        progressBar.initBar(message[0]);
        String pretext = "";
        if (message.length > 1) pretext = message[1];
        progressBar.print(pretext + "\n");
    }

    public static void setPeriod(int period) {
        progressBar.period = period;
    }

    private synchronized void print(String pretext) {
        String time = "";
        if (printTimer) {
            time = period / 3600000 + ":" + period % 3600000 / 60000 + ":" + period % 3600000 % 60000 / 1000;
            period -= 250;
        }
        System.out.printf("%s%s%s%s", pretext, message, bar.toString(), time);
    }
}
