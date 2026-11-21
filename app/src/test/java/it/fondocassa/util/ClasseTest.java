package it.fondocassa.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClasseTest {
    @Test
    public void annoESezioneDiventanoLaFormaConLaA() {
        assertEquals("1ª C", Classe.normalizza("1C"));
        assertEquals("1ª C", Classe.normalizza("1c"));
        assertEquals("1ª C", Classe.normalizza(" 1 c "));
        assertEquals("1ª C", Classe.normalizza("1aC"));
        assertEquals("1ª C", Classe.normalizza("1ªC"));
        assertEquals("1ª C", Classe.normalizza("1ª C"));
        assertEquals("3ª B", Classe.normalizza("3°b"));
        assertEquals("5ª A", Classe.normalizza("5^A"));
    }

    @Test
    public void altriTestiRestanoComeSonoEntroCinqueCaratteri() {
        assertEquals("", Classe.normalizza(null));
        assertEquals("", Classe.normalizza("   "));
        assertEquals("IV", Classe.normalizza(" IV "));
        assertEquals("Ranoc", Classe.normalizza("Ranocchi"));
        assertEquals("2ª Bm", Classe.normalizza("2ª Bm"));
        assertEquals(4, Classe.normalizza("1C").length());
    }
}
