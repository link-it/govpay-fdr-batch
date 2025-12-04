package it.govpay.fdr.batch.step4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;

import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.repository.FrTempRepository;

/**
 * Unit tests for FdrPaymentsReader (partitioner-based)
 */
@ExtendWith(MockitoExtension.class)
class FdrPaymentsReaderTest {

    @Mock
    private FrTempRepository frTempRepository;

    private FdrPaymentsReader reader;

    private static final String TEST_COD_DOMINIO = "12345678901";
    private static final int TEST_PARTITION_NUMBER = 1;
    private static final int TEST_TOTAL_PARTITIONS = 5;

    @BeforeEach
    void setUp() throws Exception {
        reader = new FdrPaymentsReader(frTempRepository);

        // Simula l'iniezione di @Value da ExecutionContext usando reflection
        setField(reader, "codDominio", TEST_COD_DOMINIO);
        setField(reader, "partitionNumber", TEST_PARTITION_NUMBER);
        setField(reader, "totalPartitions", TEST_TOTAL_PARTITIONS);
    }

    @Test
    @DisplayName("Should read all flows for assigned domain")
    void testReadAllFlowsForDomain() throws Exception {
        // Given: 10 flows for the domain
        List<FrTemp> flussi = createFrTempList(10, TEST_COD_DOMINIO);
        when(frTempRepository.findByCodDominioOrderByDataOraPubblicazioneAsc(TEST_COD_DOMINIO))
            .thenReturn(flussi);

        // When: Open reader and read all
        reader.open(new ExecutionContext());

        List<FrTemp> results = new ArrayList<>();
        FrTemp flussoTemp;
        while ((flussoTemp = reader.read()) != null) {
            results.add(flussoTemp);
        }

        // Then: Should read all 10 flows
        assertThat(results).hasSize(10);
        verify(frTempRepository).findByCodDominioOrderByDataOraPubblicazioneAsc(TEST_COD_DOMINIO);
    }

    @Test
    @DisplayName("Should return null when domain has no flows")
    void testReadNoFlows() throws Exception {
        // Given: Empty list for domain
        when(frTempRepository.findByCodDominioOrderByDataOraPubblicazioneAsc(TEST_COD_DOMINIO))
            .thenReturn(new ArrayList<>());

        // When: Open and read
        reader.open(new ExecutionContext());
        FrTemp result = reader.read();

        // Then: Should return null immediately
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should read flows in correct order")
    void testReadInOrder() throws Exception {
        // Given: Flows with sequential codes
        List<FrTemp> flussi = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            FrTemp frTemp = FrTemp.builder()
                .id((long) i)
                .codDominio(TEST_COD_DOMINIO)
                .codFlusso("FDR-" + String.format("%03d", i))
                .idPsp("PSP001")
                .revisione(1L)
                .dataOraPubblicazione(Instant.now())
                .build();
            flussi.add(frTemp);
        }

        when(frTempRepository.findByCodDominioOrderByDataOraPubblicazioneAsc(TEST_COD_DOMINIO))
            .thenReturn(flussi);

        // When: Read all
        reader.open(new ExecutionContext());
        List<FrTemp> results = new ArrayList<>();
        FrTemp flussoTemp;
        while ((flussoTemp = reader.read()) != null) {
            results.add(flussoTemp);
        }

        // Then: Should maintain order
        assertThat(results).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(results.get(i).getCodFlusso()).isEqualTo("FDR-" + String.format("%03d", i));
        }
    }

    @Test
    @DisplayName("Should handle single flow")
    void testReadSingleFlow() throws Exception {
        // Given: Single flow
        List<FrTemp> flussi = createFrTempList(1, TEST_COD_DOMINIO);
        when(frTempRepository.findByCodDominioOrderByDataOraPubblicazioneAsc(TEST_COD_DOMINIO))
            .thenReturn(flussi);

        // When: Read
        reader.open(new ExecutionContext());
        FrTemp first = reader.read();
        FrTemp second = reader.read();

        // Then: First should have value, second should be null
        assertThat(first).isNotNull();
        assertThat(second).isNull();
    }

    @Test
    @DisplayName("Should only initialize once on open")
    void testInitializeOnce() throws Exception {
        // Given
        List<FrTemp> flussi = createFrTempList(3, TEST_COD_DOMINIO);
        when(frTempRepository.findByCodDominioOrderByDataOraPubblicazioneAsc(TEST_COD_DOMINIO))
            .thenReturn(flussi);

        // When: Open and read multiple times
        reader.open(new ExecutionContext());
        reader.read();
        reader.read();
        reader.read();
        reader.read(); // Should return null

        // Then: Repository should be called only once
        verify(frTempRepository).findByCodDominioOrderByDataOraPubblicazioneAsc(TEST_COD_DOMINIO);
    }

    @Test
    @DisplayName("Should handle close properly")
    void testClose() throws Exception {
        // Given
        List<FrTemp> flussi = createFrTempList(5, TEST_COD_DOMINIO);
        when(frTempRepository.findByCodDominioOrderByDataOraPubblicazioneAsc(TEST_COD_DOMINIO))
            .thenReturn(flussi);

        // When: Open, read some, then close
        reader.open(new ExecutionContext());
        reader.read();
        reader.close();

        // Then: Should not throw exception
        // Further reads after close would require reopen
    }

    @Test
    @DisplayName("Should handle large dataset for single domain")
    void testReadLargeDataset() throws Exception {
        // Given: 100 flows for one domain
        List<FrTemp> flussi = createFrTempList(100, TEST_COD_DOMINIO);
        when(frTempRepository.findByCodDominioOrderByDataOraPubblicazioneAsc(TEST_COD_DOMINIO))
            .thenReturn(flussi);

        // When: Read all
        reader.open(new ExecutionContext());
        int count = 0;
        while (reader.read() != null) {
            count++;
        }

        // Then: Should read all 100 flows
        assertThat(count).isEqualTo(100);
    }

    private List<FrTemp> createFrTempList(int size, String codDominio) {
        List<FrTemp> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            FrTemp frTemp = FrTemp.builder()
                .id((long) i)
                .codDominio(codDominio)
                .codFlusso("FDR-" + String.format("%05d", i))
                .idPsp("PSP001")
                .revisione(1L)
                .dataOraPubblicazione(Instant.now())
                .build();
            list.add(frTemp);
        }
        return list;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
