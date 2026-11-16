package it.fondocassa.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Date salvate in ISO (yyyy-MM-dd), mostrate in formato italiano (dd/MM/yyyy). */
public final class Date {
    private static final DateTimeFormatter ITALIANO = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private Date() {
    }

    public static String oggi() {
        return LocalDate.now().toString();
    }

    public static String iso(int anno, int mese, int giorno) {
        return LocalDate.of(anno, mese, giorno).toString();
    }

    public static LocalDate parse(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException | NullPointerException e) {
            return LocalDate.now();
        }
    }

    public static String mostra(String iso) {
        if (iso == null || iso.isEmpty()) {
            return "";
        }
        try {
            return LocalDate.parse(iso).format(ITALIANO);
        } catch (DateTimeParseException e) {
            return iso;
        }
    }
}
