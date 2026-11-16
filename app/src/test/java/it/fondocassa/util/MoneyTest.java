package it.fondocassa.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class MoneyTest {
    @Test
    public void parseAccettaVirgolaEPunto() {
        assertEquals(1250, Money.parse("12,50"));
        assertEquals(1250, Money.parse("12.50"));
        assertEquals(1250, Money.parse("12,5"));
        assertEquals(500, Money.parse("5"));
        assertEquals(5, Money.parse("0,05"));
    }

    @Test
    public void parseAccettaSimboloSpaziEMigliaia() {
        assertEquals(123456, Money.parse("€ 1.234,56"));
        assertEquals(123456, Money.parse("1,234.56"));
        assertEquals(1000, Money.parse(" 10 "));
    }

    @Test
    public void parseRifiutaValoriNonValidi() {
        assertThrows(IllegalArgumentException.class, () -> Money.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Money.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("-5"));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("1,234"));
    }

    @Test
    public void formatUsaIlFormatoItaliano() {
        assertEquals("€ 0,00", Money.format(0));
        assertEquals("€ 5,00", Money.format(500));
        assertEquals("€ 1.234,56", Money.format(123456));
        assertEquals("€ -3,50", Money.format(-350));
    }

    @Test
    public void formatInputSiRileggeConParse() {
        assertEquals("1234,56", Money.formatInput(123456));
        for (long c : new long[]{0, 1, 99, 100, 500, 123456}) {
            assertEquals(c, Money.parse(Money.formatInput(c)));
        }
    }
}
