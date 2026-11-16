package it.fondocassa.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Hash del PIN con PBKDF2 e sale casuale: il PIN non viene mai salvato in chiaro. */
public final class PinHasher {
    public static final int LUNGHEZZA_MINIMA = 4;
    private static final int ITERAZIONI = 60_000;
    private static final int BIT_CHIAVE = 256;

    private PinHasher() {
    }

    public static boolean valido(String pin) {
        return pin != null && pin.length() >= LUNGHEZZA_MINIMA && pin.chars().allMatch(Character::isDigit);
    }

    /** Restituisce una stringa "iterazioni:sale:hash" da salvare nelle preferenze. */
    public static String hash(String pin) {
        byte[] sale = new byte[16];
        new SecureRandom().nextBytes(sale);
        return ITERAZIONI + ":" + Base64.getEncoder().encodeToString(sale) + ":"
                + Base64.getEncoder().encodeToString(deriva(pin, sale, ITERAZIONI));
    }

    public static boolean verifica(String pin, String salvato) {
        if (pin == null || salvato == null) {
            return false;
        }
        String[] parti = salvato.split(":");
        if (parti.length != 3) {
            return false;
        }
        try {
            int iterazioni = Integer.parseInt(parti[0]);
            byte[] sale = Base64.getDecoder().decode(parti[1]);
            byte[] atteso = Base64.getDecoder().decode(parti[2]);
            return MessageDigest.isEqual(atteso, deriva(pin, sale, iterazioni));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] deriva(String pin, byte[] sale, int iterazioni) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), sale, iterazioni, BIT_CHIAVE);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 non disponibile", e);
        }
    }
}
