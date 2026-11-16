package it.fondocassa.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.LocalDate;

public class DateTest {
    @Test
    public void isoEFormatoItaliano() {
        assertEquals("2026-03-07", Date.iso(2026, 3, 7));
        assertEquals("07/03/2026", Date.mostra("2026-03-07"));
        assertEquals(LocalDate.of(2026, 3, 7), Date.parse("2026-03-07"));
    }

    @Test
    public void valoriNonValidiNonFannoCrashare() {
        assertEquals("", Date.mostra(null));
        assertEquals("", Date.mostra(""));
        assertEquals("boh", Date.mostra("boh"));
        assertEquals(LocalDate.now(), Date.parse("boh"));
        assertEquals(LocalDate.now(), Date.parse(null));
    }
}
