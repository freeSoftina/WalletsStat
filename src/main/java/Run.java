public class Run {

    public static void main(String[] args) {
        ProgressBar.run();
        while (true) {
            WalletsStat walletsStat = WalletsStat.init();
            int period = walletsStat.getPeriod();
            ProgressBar.setPeriod(period);
            walletsStat.scanBlocks();
            walletsStat = null;
            System.gc();
            try {
                Thread.sleep(period);
            } catch (InterruptedException ignored) { }
        }
    }
}
