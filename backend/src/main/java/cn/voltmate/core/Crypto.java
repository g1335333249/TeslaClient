package cn.voltmate.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Crypto {
    private final SecureRandom random = new SecureRandom();
    private final byte[] key;
    public Crypto(@Value("${app.token-key-base64:}") String keyBase64, @Value("${app.mode:mock}") String mode) {
        if (keyBase64.isBlank()) {
            if ("real".equals(mode)) throw new IllegalStateException("APP_TOKEN_KEY_BASE64 is required in real mode");
            key = new byte[32]; random.nextBytes(key);
        } else {
            key = Base64.getDecoder().decode(keyBase64);
            if (key.length != 32) throw new IllegalStateException("APP_TOKEN_KEY_BASE64 must encode 32 bytes");
        }
    }
    public String randomUrlToken() { byte[] b=new byte[32]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public String encrypt(String value) {
        try {
            byte[] iv=new byte[12]; random.nextBytes(iv);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            byte[] encrypted=c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result=new byte[iv.length+encrypted.length]; System.arraycopy(iv,0,result,0,iv.length); System.arraycopy(encrypted,0,result,iv.length,encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public String decrypt(String value) {
        try {
            byte[] data=Base64.getDecoder().decode(value);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,data,0,12));
            return new String(c.doFinal(data,12,data.length-12),StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalStateException("Token decryption failed",e); }
    }
}
