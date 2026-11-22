package live.passo.smsgateway;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.os.Build;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/** Keeps the phone's decryption key non-exportable and OTP payloads off the app UI. */
final class GatewayCrypto {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String RSA_ALIAS = "passo.sms.gateway.rsa.v1";
    private static final String AES_ALIAS = "passo.sms.gateway.token.v1";

    private GatewayCrypto() { }

    static String publicKeyPem() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (!store.containsAlias(RSA_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE);
            KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(RSA_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setRandomizedEncryptionRequired(true);
            // Android 15+ restricts RSA-OAEP MGF1 to the digest authorized at
            // key creation. The server encrypts OTPs with SHA-256 for both OAEP
            // and MGF1, so authorize that exact digest on supported devices.
            if (Build.VERSION.SDK_INT >= 35) {
                spec.setMgf1Digests(KeyProperties.DIGEST_SHA256);
            }
            generator.initialize(spec.build());
            generator.generateKeyPair();
        }
        Certificate certificate = store.getCertificate(RSA_ALIAS);
        if (certificate == null) throw new IllegalStateException("Phone encryption key is unavailable.");
        String encoded = Base64.encodeToString(certificate.getPublicKey().getEncoded(), Base64.NO_WRAP);
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    }

    static boolean hasRsaKey() {
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            return store.containsAlias(RSA_ALIAS);
        } catch (Exception ignored) { return false; }
    }

    static JSONObject decryptOtpPayload(String encryptedPayload) throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        PrivateKey privateKey = (PrivateKey) store.getKey(RSA_ALIAS, null);
        if (privateKey == null) throw new IllegalStateException("Phone decryption key is missing.");
        byte[] ciphertext = Base64.decode(encryptedPayload, Base64.DEFAULT);
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            OAEPParameterSpec oaep = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                    PSource.PSpecified.DEFAULT);
            cipher.init(Cipher.DECRYPT_MODE, privateKey, oaep);
            plaintext = cipher.doFinal(ciphertext);
            return new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(ciphertext, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    static String encryptDeviceToken(String token) throws Exception {
        SecretKey key = aesKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        try { return Base64.encodeToString(combined, Base64.NO_WRAP); }
        finally { Arrays.fill(encrypted, (byte) 0); Arrays.fill(combined, (byte) 0); }
    }

    static String decryptDeviceToken(String ciphertext) throws Exception {
        byte[] combined = Base64.decode(ciphertext, Base64.NO_WRAP);
        byte[] iv = Arrays.copyOfRange(combined, 0, 12);
        byte[] encrypted = Arrays.copyOfRange(combined, 12, combined.length);
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(128, iv));
            plaintext = cipher.doFinal(encrypted);
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(combined, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    static void deleteKeys() {
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            store.deleteEntry(RSA_ALIAS);
            store.deleteEntry(AES_ALIAS);
        } catch (Exception ignored) { }
    }

    private static SecretKey aesKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(AES_ALIAS)) return (SecretKey) store.getKey(AES_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
