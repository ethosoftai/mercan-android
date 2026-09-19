package ai.mercan.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * At-rest encryption for the model file (envelope scheme).
 *
 * <pre>
 *   Android Keystore (hardware-backed, non-exportable) AES-256-GCM master key
 *       └─ wraps a random 256-bit data key (stored wrapped in SharedPreferences)
 *            └─ AES-256-CTR streams the model; SHA-256 of the plaintext is stored
 *               alongside for integrity.
 * </pre>
 *
 * File layout: MAGIC(8) | IV(16) | ciphertext. A copied .enc file is useless on another
 * device because the data key can only be unwrapped by this device's Keystore.
 */
public final class ModelCrypto {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String MASTER_ALIAS = "mercan_master_v1";
    private static final String PREFS = "mercan_keys";
    private static final String PREF_WRAPPED = "data_key_v1";
    private static final byte[] MAGIC = "MRCNENC1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_LEN = 16;
    private static final int GCM_IV_LEN = 12;

    private ModelCrypto() {}

    // ------------------------------------------------------------- keys
    private static SecretKey masterKey() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(MASTER_ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    /** The per-install data key, created on first use and kept only in wrapped form. */
    static synchronized SecretKey dataKey(Context ctx) throws GeneralSecurityException, IOException {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String wrapped = sp.getString(PREF_WRAPPED, null);
        SecretKey master = masterKey();
        if (wrapped != null) {
            byte[] blob = Base64.decode(wrapped, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, master, new GCMParameterSpec(128, blob, 0, GCM_IV_LEN));
            byte[] raw = c.doFinal(blob, GCM_IV_LEN, blob.length - GCM_IV_LEN);
            return new SecretKeySpec(raw, "AES");
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, master);
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(raw);
        byte[] blob = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, blob, 0, iv.length);
        System.arraycopy(ct, 0, blob, iv.length, ct.length);
        sp.edit().putString(PREF_WRAPPED, Base64.encodeToString(blob, Base64.NO_WRAP)).apply();
        Arrays.fill(raw, (byte) 0);
        return dataKey(ctx);
    }

    /** Forgets the data key (all encrypted models become unreadable). */
    static void resetKeys(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_WRAPPED).apply();
    }

    // ---------------------------------------------------------- streams
    /** Output stream that encrypts into {@code out}; {@link EncryptingOutputStream#digestHex()} after close. */
    public static final class EncryptingOutputStream extends FilterOutputStream {
        private final MessageDigest digest;
        private String hex;

        private EncryptingOutputStream(OutputStream cipherOut, MessageDigest digest) {
            super(cipherOut);
            this.digest = digest;
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            digest.update(b, off, len);
            out.write(b, off, len); // FilterOutputStream's default would go byte-by-byte
        }
        @Override public void write(int b) throws IOException { digest.update((byte) b); out.write(b); }
        @Override public void close() throws IOException {
            super.close();
            if (hex == null) hex = toHex(digest.digest());
        }
        public String digestHex() { return hex; }
    }

    public static EncryptingOutputStream encrypt(Context ctx, File out) throws IOException {
        try {
            SecretKey key = dataKey(ctx);
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(MAGIC);
            fos.write(iv);
            return new EncryptingOutputStream(new CipherOutputStream(fos, c), MessageDigest.getInstance("SHA-256"));
        } catch (GeneralSecurityException e) {
            throw new IOException("encryption unavailable: " + e.getMessage(), e);
        }
    }

    /** Input stream yielding plaintext; {@link DecryptingInputStream#digestHex()} after EOF. */
    public static final class DecryptingInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private String hex;

        private DecryptingInputStream(InputStream in, MessageDigest digest) {
            super(in);
            this.digest = digest;
        }
        @Override public int read() throws IOException {
            int b = in.read();
            if (b >= 0) digest.update((byte) b);
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) digest.update(b, off, n);
            return n;
        }
        public String digestHex() {
            if (hex == null) hex = toHex(digest.digest());
            return hex;
        }
    }

    public static DecryptingInputStream decrypt(Context ctx, File in) throws IOException {
        FileInputStream fis = new FileInputStream(in);
        try {
            byte[] magic = new byte[MAGIC.length];
            byte[] iv = new byte[IV_LEN];
            if (!readFully(fis, magic) || !Arrays.equals(magic, MAGIC) || !readFully(fis, iv)) {
                fis.close();
                throw new IOException("not an encrypted Mercan model");
            }
            SecretKey key = dataKey(ctx);
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            return new DecryptingInputStream(new CipherInputStream(fis, c), MessageDigest.getInstance("SHA-256"));
        } catch (GeneralSecurityException e) {
            fis.close();
            throw new IOException("decryption unavailable: " + e.getMessage(), e);
        }
    }

    public static boolean isEncrypted(File f) {
        if (!f.isFile() || f.length() < MAGIC.length + IV_LEN) return false;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] magic = new byte[MAGIC.length];
            return readFully(fis, magic) && Arrays.equals(magic, MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
