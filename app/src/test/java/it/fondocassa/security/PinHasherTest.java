package it.fondocassa.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PinHasherTest {
    @Test
    public void ilPinCorrettoVieneVerificato() {
        String hash = PinHasher.hash("1234");
        assertTrue(PinHasher.verifica("1234", hash));
        assertFalse(PinHasher.verifica("1235", hash));
        assertFalse(PinHasher.verifica("", hash));
        assertFalse(PinHasher.verifica(null, hash));
    }

    @Test
    public void ilPinNonCompareNellHashEIlSaleCambiaOgniVolta() {
        String primo = PinHasher.hash("482915");
        String secondo = PinHasher.hash("482915");
        assertFalse(primo.contains("482915"));
        assertNotEquals(primo, secondo);
        assertTrue(PinHasher.verifica("482915", secondo));
    }

    @Test
    public void hashCorrottiVengonoRifiutati() {
        assertFalse(PinHasher.verifica("1234", null));
        assertFalse(PinHasher.verifica("1234", ""));
        assertFalse(PinHasher.verifica("1234", "non:valido"));
        assertFalse(PinHasher.verifica("1234", "x:@@:@@"));
    }

    @Test
    public void validazioneDelPin() {
        assertTrue(PinHasher.valido("1234"));
        assertTrue(PinHasher.valido("123456"));
        assertFalse(PinHasher.valido("123"));
        assertFalse(PinHasher.valido("12a4"));
        assertFalse(PinHasher.valido(null));
    }
}
