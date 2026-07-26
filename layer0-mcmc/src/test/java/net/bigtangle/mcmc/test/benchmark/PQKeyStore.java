package net.bigtangle.mcmc.test.benchmark;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import net.bigtangle.core.PQKey;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.KeyBundle;

public class PQKeyStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static void save(File file, List<PQKey> keys) throws Exception {
        List<KeyEntry> entries = new ArrayList<>();
        for (PQKey k : keys) {
            KeyEntry e = new KeyEntry();
            e.privML = k.getPrivateKeyMlAsHex();
            e.privSLH = k.getPrivateKeySlhAsHex() != null ? k.getPrivateKeySlhAsHex() : "";
            e.pubBundle = Utils.HEX.encode(k.getKeyBundleBytes());
            entries.add(e);
        }
        KeyFile kf = new KeyFile();
        kf.version = 1;
        kf.count = entries.size();
        kf.keys = entries;
        try (OutputStream os = new FileOutputStream(file)) {
            MAPPER.writeValue(os, kf);
        }
    }

    public static List<PQKey> load(File file) throws Exception {
        try (InputStream is = new FileInputStream(file)) {
            KeyFile kf = MAPPER.readValue(is, KeyFile.class);
            List<PQKey> keys = new ArrayList<>(kf.count);
            for (KeyEntry e : kf.keys) {
                byte[] mlPriv = Utils.HEX.decode(e.privML);
                byte[] slhPriv = e.privSLH.isEmpty() ? null : Utils.HEX.decode(e.privSLH);
                KeyBundle bundle = KeyBundle.deserialize(Utils.HEX.decode(e.pubBundle));
                keys.add(PQKey.fromPrivateKeyBundle(mlPriv, slhPriv, bundle));
            }
            return keys;
        }
    }

    public static class KeyFile {
        public int version;
        public int count;
        public List<KeyEntry> keys;
    }

    public static class KeyEntry {
        public String privML;
        public String privSLH;
        public String pubBundle;
    }

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        boolean mldsaOnly = args.length > 1 && "ml".equals(args[1]);
        File out = new File("helper/testpq.json");
        System.out.println("Generating " + n + " " + (mldsaOnly ? "ML-DSA-only" : "dual") + " PQKeys...");
        long start = System.currentTimeMillis();
        List<PQKey> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            keys.add(mldsaOnly ? PQKey.createNewMLDSA() : PQKey.createNew());
            if ((i + 1) % 100 == 0) {
                System.out.println("  " + (i + 1) + "/" + n + " keys (" +
                        (System.currentTimeMillis() - start) / 1000 + "s)");
            }
        }
        save(out, keys);
        long elapsed = (System.currentTimeMillis() - start) / 1000;
        System.out.println("Saved " + n + " keys to " + out + " in " + elapsed + "s");
    }
}
