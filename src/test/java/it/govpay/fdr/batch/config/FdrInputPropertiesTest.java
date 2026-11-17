package it.govpay.fdr.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@DisplayName("FdrInputProperties Tests")
class FdrInputPropertiesTest {

    private static FdrInputProperties bind(Map<String, String> valori) {
        return new Binder(new MapConfigurationPropertySource(valori))
            .bindOrCreate("govpay.fdr.input", FdrInputProperties.class);
    }

    @Test
    @DisplayName("Default: .json e .xml, validazione XML attiva")
    void defaults() {
        FdrInputProperties properties = bind(Map.of());

        assertThat(properties.getExtension()).containsExactly(".json", ".xml");
        assertThat(properties.getXml().isValidate()).isTrue();
    }

    @Test
    @DisplayName("Un valore singolo, come nelle configurazioni precedenti, resta valido")
    void valoreSingolo() {
        FdrInputProperties properties = bind(Map.of("govpay.fdr.input.extension", ".json"));

        assertThat(properties.getExtension()).containsExactly(".json");
        assertThat(properties.isEstensioneAmmessa("flusso.json")).isTrue();
        assertThat(properties.isEstensioneAmmessa("flusso.xml")).isFalse();
    }

    @Test
    @DisplayName("Lista separata da virgole e validazione disattivabile")
    void listaEValidazione() {
        FdrInputProperties properties = bind(Map.of(
            "govpay.fdr.input.extension", ".json, .xml",
            "govpay.fdr.input.xml.validate", "false"));

        assertThat(properties.isEstensioneAmmessa("flusso.json")).isTrue();
        assertThat(properties.isEstensioneAmmessa("FLUSSO.XML")).isTrue();
        assertThat(properties.isEstensioneAmmessa("flusso.txt")).isFalse();
        assertThat(properties.getXml().isValidate()).isFalse();
    }
}
