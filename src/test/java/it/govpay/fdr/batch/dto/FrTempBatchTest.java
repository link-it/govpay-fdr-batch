package it.govpay.fdr.batch.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.govpay.fdr.batch.entity.FrTemp;

/**
 * Test per FrTempBatch DTO
 */
@DisplayName("FrTempBatch Tests")
class FrTempBatchTest {

    private FrTemp createFrTemp(String codFlusso) {
        return FrTemp.builder()
            .codDominio("12345678901")
            .codFlusso(codFlusso)
            .codPsp("AGID_01")
            .idPsp("PSP_12345")
            .dataOraFlusso(LocalDateTime.now())
            .build();
    }

    @Test
    @DisplayName("Test constructor with no args")
    void testNoArgsConstructor() {
        FrTempBatch batch = new FrTempBatch();

        assertNotNull(batch);
        assertNull(batch.getCodDominio());
        assertNull(batch.getFlussi());
    }

    @Test
    @DisplayName("Test constructor with all args")
    void testAllArgsConstructor() {
        List<FrTemp> flussi = new ArrayList<>();
        flussi.add(createFrTemp("FDR-001"));
        flussi.add(createFrTemp("FDR-002"));

        FrTempBatch batch = new FrTempBatch("12345678901", flussi);

        assertNotNull(batch);
        assertEquals("12345678901", batch.getCodDominio());
        assertEquals(2, batch.getFlussi().size());
        assertEquals("FDR-001", batch.getFlussi().get(0).getCodFlusso());
    }

    @Test
    @DisplayName("Test builder pattern")
    void testBuilder() {
        List<FrTemp> flussi = new ArrayList<>();
        flussi.add(createFrTemp("FDR-001"));

        FrTempBatch batch = FrTempBatch.builder()
            .codDominio("12345678901")
            .flussi(flussi)
            .build();

        assertNotNull(batch);
        assertEquals("12345678901", batch.getCodDominio());
        assertEquals(1, batch.getFlussi().size());
    }

    @Test
    @DisplayName("Test getters and setters")
    void testGettersAndSetters() {
        FrTempBatch batch = new FrTempBatch();

        List<FrTemp> flussi = new ArrayList<>();
        flussi.add(createFrTemp("FDR-001"));

        batch.setCodDominio("12345678901");
        batch.setFlussi(flussi);

        assertEquals("12345678901", batch.getCodDominio());
        assertEquals(1, batch.getFlussi().size());
        assertEquals("FDR-001", batch.getFlussi().get(0).getCodFlusso());
    }

    @Test
    @DisplayName("Test size() with null flussi")
    void testSizeWithNullFlussi() {
        FrTempBatch batch = new FrTempBatch();
        batch.setCodDominio("12345678901");
        batch.setFlussi(null);

        assertEquals(0, batch.size());
    }

    @Test
    @DisplayName("Test size() with empty flussi")
    void testSizeWithEmptyFlussi() {
        FrTempBatch batch = new FrTempBatch();
        batch.setCodDominio("12345678901");
        batch.setFlussi(new ArrayList<>());

        assertEquals(0, batch.size());
    }

    @Test
    @DisplayName("Test size() with multiple flussi")
    void testSizeWithMultipleFlussi() {
        List<FrTemp> flussi = new ArrayList<>();
        flussi.add(createFrTemp("FDR-001"));
        flussi.add(createFrTemp("FDR-002"));
        flussi.add(createFrTemp("FDR-003"));

        FrTempBatch batch = new FrTempBatch("12345678901", flussi);

        assertEquals(3, batch.size());
    }

    @Test
    @DisplayName("Test equals with same object")
    void testEqualsSameObject() {
        FrTempBatch batch = new FrTempBatch("12345678901", new ArrayList<>());

        assertEquals(batch, batch);
    }

    @Test
    @DisplayName("Test equals with equal objects")
    void testEqualsEqualObjects() {
        List<FrTemp> flussi = new ArrayList<>();
        flussi.add(createFrTemp("FDR-001"));

        FrTempBatch batch1 = new FrTempBatch("12345678901", flussi);
        FrTempBatch batch2 = new FrTempBatch("12345678901", flussi);

        assertEquals(batch1, batch2);
    }

    @Test
    @DisplayName("Test equals with different objects")
    void testEqualsDifferentObjects() {
        FrTempBatch batch1 = new FrTempBatch("12345678901", new ArrayList<>());
        FrTempBatch batch2 = new FrTempBatch("98765432109", new ArrayList<>());

        assertNotEquals(batch1, batch2);
    }

    @Test
    @DisplayName("Test equals with null")
    void testEqualsWithNull() {
        FrTempBatch batch = new FrTempBatch("12345678901", new ArrayList<>());

        assertNotEquals(null, batch);
    }

    @Test
    @DisplayName("Test hashCode consistency")
    void testHashCodeConsistency() {
        List<FrTemp> flussi = new ArrayList<>();
        flussi.add(createFrTemp("FDR-001"));

        FrTempBatch batch1 = new FrTempBatch("12345678901", flussi);
        FrTempBatch batch2 = new FrTempBatch("12345678901", flussi);

        assertEquals(batch1.hashCode(), batch2.hashCode());
    }

    @Test
    @DisplayName("Test toString contains expected fields")
    void testToString() {
        List<FrTemp> flussi = new ArrayList<>();
        flussi.add(createFrTemp("FDR-001"));

        FrTempBatch batch = new FrTempBatch("12345678901", flussi);

        String toString = batch.toString();
        assertNotNull(toString);
        assertTrue(toString.contains("12345678901"));
        assertTrue(toString.contains("FrTempBatch"));
    }

    @Test
    @DisplayName("Test modify flussi list")
    void testModifyFlussiList() {
        List<FrTemp> flussi = new ArrayList<>();
        flussi.add(createFrTemp("FDR-001"));

        FrTempBatch batch = new FrTempBatch("12345678901", flussi);
        assertEquals(1, batch.size());

        // Add more elements to the list
        batch.getFlussi().add(createFrTemp("FDR-002"));
        assertEquals(2, batch.size());
    }

    @Test
    @DisplayName("Test builder with null values")
    void testBuilderWithNullValues() {
        FrTempBatch batch = FrTempBatch.builder()
            .codDominio(null)
            .flussi(null)
            .build();

        assertNotNull(batch);
        assertNull(batch.getCodDominio());
        assertNull(batch.getFlussi());
        assertEquals(0, batch.size());
    }
}
