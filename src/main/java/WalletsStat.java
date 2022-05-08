import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.ConnectException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;


public class WalletsStat {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Properties prop = new Properties();
    private final TreeMap<String, long[]> wallets;
    private final long[][] stat;
    private final String walletsPath;
    private final String statPath;

    private WalletsStat() throws IOException {
        String path = WalletsStat.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        path = path.substring(System.getProperty("os.name").startsWith("Windows") ? 1 : 0, path.lastIndexOf("/") + 1);
        String propPath = path + "conf/prop";
        walletsPath = path + "baze/wallets";
        statPath = path + "baze/stat";
        if (!Files.exists(Paths.get(path + "baze"))) createBaze(path + "baze");
        if (!Files.exists(Paths.get(path + "conf"))) createConf(path + "conf");
        try (FileReader walletsReader = new FileReader(walletsPath); FileReader statReader = new FileReader(statPath)
             ; FileReader propReader = new FileReader(propPath)) {
            walletsReader.skip(10); // skip "wallets = "
            wallets = objectMapper.readValue(walletsReader, new TypeReference<TreeMap<String, long[]>>() {});
            statReader.skip(7); // skip "stat = "
            stat = objectMapper.readValue(statReader, long[][].class);
            prop.load(propReader);
        }
    }

    public static WalletsStat init() {
        WalletsStat walletsStat = null;
        try {
            walletsStat = new WalletsStat();
        } catch (IOException ignored) {}
        return walletsStat;
    }

    public int getPeriod() {
        return Integer.parseInt(String.valueOf(prop.getOrDefault("period", "43200000")));
    }

    public void scanBlocks() {
        ArrayList<long[]> buffer = new ArrayList<>();
        long blockHeight = stat[0][12];
        if (blockHeight > 0) blockHeight++;
        String host = prop.getOrDefault("host", "http://localhost:9976").toString();
        String url = host + "/prizm?requestType=";
        long ecBlockHeight = 0;
        try {
            ecBlockHeight = objectMapper.readTree(new URL(url + "getECBlock")).get("ecBlockHeight").asLong();
            ProgressBar.printMessage("block height - " + blockHeight, "ok");
            String blockId = objectMapper.readTree(new URL(url + "getBlockId&height=" + blockHeight)).get("block").asText();
            while(true) {
                JsonNode node = objectMapper.readTree(new URL(url + "getBlock&block=" + blockId));
                long[] values = new long[8];
                convert(values, node.get("generatorRS").asText(), 0);
                values[2] = node.get("totalFeeNQT").asLong();
                blockHeight = node.get("height").asLong();
                long timestamp = node.get("timestamp").asLong();
                values[3] = blockHeight;
                values[6] = timestamp;
                buffer.add(values);
                JsonNode transactions = node.get("transactions");
                for (JsonNode transactionId : transactions) {
                    JsonNode transactionNode = objectMapper.readTree(new URL(url + "getTransaction&transaction=" + transactionId.asText()));
                    values = new long[8];
                    String senderRS = transactionNode.get("senderRS").asText();
                    convert(values, senderRS, 0);
                    long amountNQT = transactionNode.get("amountNQT").asLong();
                    values[2] = -(amountNQT + transactionNode.get("feeNQT").asLong());
                    values[3] = blockHeight;
                    values[6] = timestamp;
                    values[7] = 1;
                    buffer.add(values);
                    try {
                        values = new  long[8];
                        convert(values, transactionNode.get("recipientRS").asText(), 0);
                        values[2] = amountNQT;
                        values[3] = blockHeight;
                        convert(values, senderRS, 4);
                        values[6] = timestamp;
                        values[7] = senderRS.equals("PRIZM-TE8N-B3VM-JJQH-5NYJB") ? 3 : 2;
                        buffer.add(values);
                    } catch (NullPointerException ignored) {}
                }
                for (long[] array : buffer) updateData(array);
                buffer.clear();
                stat[0][12] = blockHeight;
                stat[2][12] = timestamp;
                if (blockHeight == ecBlockHeight || blockHeight < ecBlockHeight && blockHeight % 10000 == 0) {
                    write(wallets, walletsPath, "wallets");
                    write(stat, statPath, "stat");
                }
                ProgressBar.printMessage("block height - " + (blockHeight + 1), "ok");
                try {
                    blockId = node.get("nextBlock").asText();
                } catch (NullPointerException e) {
                    System.out.println("missing");
                    break;
                }
            }
        } catch (ConnectException e) {
            System.out.println("not connecting with " + host);
        } catch (IOException ignored) {}
        finally {
            System.out.print("Block scanning is over");
            ProgressBar.printMessage("Updating statistics");
            if (blockHeight <= ecBlockHeight) {
                write(wallets, walletsPath, "wallets");
                write(stat, statPath, "stat");
            }
            updateShow();
            ProgressBar.printMessage("over", "ok");
        }
    }

    private void updateData(long[] values) {
        String addressRS = getAddressRS(values, 0);
        if (wallets.containsKey(addressRS)) {
            long[] walletStat = wallets.get(addressRS);
            int typeStructBalanceNQT = selectTypeStructBalanceNQT(walletStat[3]);
            int typePrevB = selectTypeBalanceNQT(walletStat[2]);
            long balanceNQT = walletStat[2] + values[2];
            int typeBalanceNQT = selectTypeBalanceNQT(balanceNQT);
            if (typeBalanceNQT < 12) {
                stat[typeStructBalanceNQT][typePrevB]--;
                stat[typeStructBalanceNQT + 1][typePrevB] -= walletStat[2];
                stat[typeStructBalanceNQT][typeBalanceNQT]++;
                stat[typeStructBalanceNQT + 1][typeBalanceNQT] += balanceNQT;
            } else stat[1][12] = balanceNQT;
            if (values[7] == 0) {
                if (values[2] != 0) addHoldAmount(walletStat, values);
                walletStat[6] = values[3];
            }
            if (values[7] == 1) resetHoldAmount(walletStat, values);
            if (values[7] == 2) addHoldAmount(walletStat, values);
            if (values[7] == 3) updateWalletsStructBalanceNQT(getAddressRS(walletStat, 0), values[2], 88);
            walletStat[2] = balanceNQT;
            wallets.put(addressRS, walletStat);
        } else {
            if (values[3] > 0) {
                int typeBalanceNQT = selectTypeBalanceNQT(values[2]);
                stat[0][typeBalanceNQT]++;
                stat[1][typeBalanceNQT] += values[2];
            }
            long[] walletStat = {values[4], values[5], values[2], 0, values[6], 0, 0};
            wallets.put(addressRS, walletStat);
            if (values[4] > 0) updateWalletsStructBalanceNQT(getAddressRS(values, 4), values[2], 88);
        }
    }

    private int selectTypeBalanceNQT(long balanceNQT) {
        int typeBalanceNQT = 0;
        if (balanceNQT >= 100) typeBalanceNQT = 1;
        if (balanceNQT >= 10000) typeBalanceNQT = 2;
        if (balanceNQT >= 100000) typeBalanceNQT = 3;
        if (balanceNQT >= 1000000) typeBalanceNQT = 4;
        if (balanceNQT >= 5000000) typeBalanceNQT = 5;
        if (balanceNQT >= 10000000) typeBalanceNQT = 6;
        if (balanceNQT >= 50000000) typeBalanceNQT = 7;
        if (balanceNQT >= 100000000) typeBalanceNQT = 8;
        if (balanceNQT >= 1000000000) typeBalanceNQT = 9;
        if (balanceNQT >= 10000000000L) typeBalanceNQT = 10;
        if (balanceNQT >= 100000000000L) typeBalanceNQT = 11;
        if (balanceNQT < 0) typeBalanceNQT = 12;
        return typeBalanceNQT;
    }

    private int selectTypeStructBalanceNQT(long structBalanceNQT) {
        int typeStructBalanceNQT = 0;
        if (structBalanceNQT >= 100000) typeStructBalanceNQT = 1;
        if (structBalanceNQT >= 1000000) typeStructBalanceNQT = 2;
        if (structBalanceNQT >= 10000000) typeStructBalanceNQT = 3;
        if (structBalanceNQT >= 100000000) typeStructBalanceNQT = 4;
        if (structBalanceNQT >= 1000000000) typeStructBalanceNQT = 5;
        if (structBalanceNQT >= 10000000000L) typeStructBalanceNQT = 6;
        if (structBalanceNQT >= 100000000000L) typeStructBalanceNQT = 7;
        return typeStructBalanceNQT * 4;
    }

    private void write(Object object, String path, String pretext) {
        if (pretext != null) pretext += " = ";
        else pretext = "";
        try (FileWriter fileWriter = new FileWriter(path)) {
            fileWriter.write(pretext + objectMapper.writeValueAsString(object));
        } catch (IOException ignored) {}
    }

    private void updateWalletsStructBalanceNQT(String parentRS, long amountNQT, int count) {
        if (parentRS.equals("TE8NB3VMJJQH5NYJB")) return;
        long[] walletStat = wallets.get(parentRS);
        int typeB = selectTypeBalanceNQT(walletStat[2]);
        int typePrevStructB = selectTypeStructBalanceNQT(walletStat[3]);
        walletStat[3] += amountNQT;
        int typeStructB = selectTypeStructBalanceNQT(walletStat[3]);
        stat[typePrevStructB][typeB]--;
        stat[typePrevStructB + 1][typeB] -= walletStat[2];
        stat[typeStructB][typeB]++;
        stat[typeStructB + 1][typeB] += walletStat[2];
        wallets.put(parentRS, walletStat);
        if (--count > 0 && walletStat[0] > 0)
            updateWalletsStructBalanceNQT(getAddressRS(walletStat, 0), amountNQT, count);
    }

    private void resetHoldAmount(long[] walletStat, long[] values) {
        walletStat[4] = values[6];
        walletStat[5] += values[2];
        if (walletStat[0] > 0 && walletStat[5] != 0)
            updateWalletsStructBalanceNQT(getAddressRS(walletStat, 0), walletStat[5], 88);
        walletStat[5] = 0;
    }

    private void addHoldAmount(long[] walletStat, long[] values) {
        if (isHold(walletStat, values[3])) walletStat[5] += values[2];
        else resetHoldAmount(walletStat, values);
    }

    private boolean isHold(long[] walletStat, long heightBlock) {
        return heightBlock >= 1200000 && heightBlock - walletStat[6] <= 100000 
            && 100000 <= walletStat[2] - walletStat[5] && walletStat[2] - walletStat[5] <= 11000000;
    }

    private long paraCalc(long[] walletStat, long totalB, long heightBlock) {
        long paraB = walletStat[2] - walletStat[5];
        long timeDiff = stat[2][12] - walletStat[4];
        double dailyCuff = dailyPercent(paraB) * structCuff(walletStat[3]) / 100;
        double complexCuff = Math.pow(1 + dailyCuff / 1728, timeDiff / 50d) - 1;
        double paraCuff = 1 - Math.floor(totalB / 6000000000d) / 100;
        double holdCuff = totalB < 300000000000L ? 2 * paraCuff - 1 : isHold(walletStat, heightBlock) ? 0.03 : 0.02;
        double totalCuff = Math.max(1 - totalB / 600000000000d, 0.1);
        double para = Math.floor(paraB * complexCuff * holdCuff * totalCuff);
        para = Math.min(para, Math.floor(paraB * dailyCuff * timeDiff / 86400d * paraCuff * totalCuff));
        return (long) Math.min(para, walletStat[2] > 100000000 ? 0 : 100000000 - walletStat[2]);
    }

    private double structCuff(long structBalanceNQT) {
        double structCuff = 1;
        if (structBalanceNQT >= 100000) structCuff = 2.18;
        if (structBalanceNQT >= 1000000) structCuff = 2.36;
        if (structBalanceNQT >= 10000000) structCuff = 2.77;
        if (structBalanceNQT >= 100000000) structCuff = 3.05;
        if (structBalanceNQT >= 1000000000) structCuff = 3.36;
        if (structBalanceNQT >= 10000000000L) structCuff = 3.88;
        if (structBalanceNQT >= 100000000000L) structCuff = 4.37;
        return structCuff;
    }

    private double dailyPercent(long balanceNQT) {
        double dailyPercent = 0;
        if (balanceNQT >= 100) dailyPercent = 0.12;
        if (balanceNQT >= 10000) dailyPercent = 0.14;
        if (balanceNQT >= 100000) dailyPercent = 0.18;
        if (balanceNQT >= 1000000) dailyPercent = 0.21;
        if (balanceNQT >= 5000000) dailyPercent = 0.25;
        if (balanceNQT >= 10000000) dailyPercent = 0.28;
        if (balanceNQT >= 50000000) dailyPercent = 0.33;
        if (balanceNQT >= 100000000) dailyPercent = 0;
        return dailyPercent;
    }

    private void convert(long[] array, String accountRS, int index) {
        if (accountRS.isEmpty()) {
            array[index] = 0;
            array[index + 1] = 0;
        } else {
            accountRS = accountRS.substring(6).replaceAll("-", "");
            byte[] bytes = accountRS.getBytes(StandardCharsets.UTF_8);
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(b);
            array[index] = Long.parseLong(builder.substring(0, 18));
            array[index + 1] = Long.parseLong(builder.substring(18));
        }
    }

    private String getAddressRS(long[] values, int index) {
        if (values[index] == 0) return "";
        StringBuilder res = new StringBuilder();
        String str = "" + values[index] + values[index + 1];
        for (int i = 0; i < 34; i += 2)
            res.append((char) Integer.parseInt(str.substring(i, i + 2)));
        return res.toString();
    }

    private void updateShow() {
        TreeMap<String, long[]> list = new TreeMap<>();
        List<String[]> hold = new ArrayList<>();
        long totalBalanceNQT = -stat[1][12];
        long heightBlock = stat[0][12];
        for (String key : wallets.keySet()) {
            String address = "PRIZM-" + key.substring(0, 4) + "-" + key.substring(4, 8) + "-" + key.substring(8, 12) + "-" + key.substring(12);
            long[] values = new long[4];
            long[] walletStat = wallets.get(key);
            int typeB = selectTypeBalanceNQT(walletStat[2]);
            int typeStructB = selectTypeStructBalanceNQT(walletStat[3]);
            long par = paraCalc(walletStat, totalBalanceNQT, heightBlock);
            if (isHold(walletStat, heightBlock)) {
                String[] wall = new String[7];
                wall[0] = address;
                wall[1] = walletStat[2] / 100 + "." + walletStat[2] % 100;
                wall[2] = walletStat[3] / 100 + "." + walletStat[3] % 100;
                wall[3] = String.valueOf(walletStat[4]);
                wall[4] = walletStat[5] / 100 + "." + walletStat[5] % 100;
                wall[5] = String.valueOf(walletStat[6]);
                wall[6] = String.valueOf(par / 100d);
                stat[typeStructB + 2][typeB]++;
                values[3] = 1;
                hold.add(wall);
            }
            stat[typeStructB + 3][typeB] += par;
            if (par >= 100000) {
                values[0] = walletStat[2];
                values[1] = walletStat[3];
                values[2] = par;
                list.put(address, values);
            }
        }
        write(list, walletsPath.replace("wallets", "list"), "wallets");
        write(stat, statPath.replace("stat", "show"), "stat");
        write(hold, walletsPath.replace("wallets", "hold"), null);
    }

    private void createBaze(String path) throws IOException {
        Files.createDirectory(Paths.get(path));
        try (BufferedReader walletsReader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(WalletsStat.class.getResourceAsStream("baze/wallets"))));
             BufferedReader statReader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(WalletsStat.class.getResourceAsStream("baze/stat"))));
             FileWriter walletsWriter = new FileWriter(walletsPath); FileWriter statWriter = new FileWriter(statPath)) {
             walletsWriter.write(walletsReader.readLine());
             statWriter.write(statReader.readLine());
        }
    }
    
    private void createConf(String path) throws IOException {
        Files.createDirectory(Paths.get(path));
        Files.createFile(Paths.get(path + "/prop"));
        try (FileWriter propWriter = new FileWriter(path + "/prop")) {
            propWriter.write("host=http://localhost:9976");
        }
    }
}
