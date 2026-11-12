package officemgtapp;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class Passwords {
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    static String hash(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        var encoder = Base64.getEncoder();
        return "pbkdf2-sha256$" + ITERATIONS + "$" + encoder.encodeToString(salt)
                + "$" + encoder.encodeToString(derive(password, salt, ITERATIONS));
    }

    static boolean matches(char[] password, String hash) {
        String[] parts = hash.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) {
            return false;
        }
        var decoder = Base64.getDecoder();
        return MessageDigest.isEqual(decoder.decode(parts[3]),
                derive(password, decoder.decode(parts[2]), Integer.parseInt(parts[1])));
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        var spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Password hashing is unavailable.", ex);
        } finally {
            spec.clearPassword();
        }
    }
}
