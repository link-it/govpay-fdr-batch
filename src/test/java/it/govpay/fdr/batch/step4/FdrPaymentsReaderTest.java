package it.govpay.fdr.batch.step4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.repository.FrTempRepository;

/**
 * Unit tests for FdrPaymentsReader
 */
@ExtendWith(MockitoExtension.class)
class FdrPaymentsReaderTest {

    @Mock
    private FrTempRepository frTempRepository;

    private FdrPaymentsReader reader;

    @BeforeEach
    void setUp() {
        reader = new FdrPaymentsReader(frTempRepository);
    }

    @Test
    @DisplayName("Should read all unprocessed FrTemp records with pagination")
    void testReadWithPagination() throws Exception {
        // Given: 3 pages of records (50 + 50 + 20 = 120 total)
        List<FrTemp> page1 = createFrTempList(50, 0);
        List<FrTemp> page2 = createFrTempList(50, 50);
        List<FrTemp> page3 = createFrTempList(20, 100);

        when(frTempRepository.findByOrderByDataOraPubblicazioneAsc(any(Pageable.class)))
            .thenReturn(new PageImpl<>(page1))
            .thenReturn(new PageImpl<>(page2))
            .thenReturn(new PageImpl<>(page3))
            .thenReturn(Page.empty());

        // When: Read all records
        int count = 0;
        FrTemp record;
        while ((record = reader.read()) != null) {
            count++;
            assertThat(record).isNotNull();
        }

        // Then: Should read all 120 records
        assertThat(count).isEqualTo(120);
        verify(frTempRepository, times(4)).findByOrderByDataOraPubblicazioneAsc(any(Pageable.class));
    }

    @Test
    @DisplayName("Should return null when no records found")
    void testReadNoRecords() throws Exception {
        // Given: Empty repository
        when(frTempRepository.findByOrderByDataOraPubblicazioneAsc(any(Pageable.class)))
            .thenReturn(Page.empty());

        // When: Read
        FrTemp result = reader.read();

        // Then: Should return null (repository called twice: init + else-if check)
        assertThat(result).isNull();
        verify(frTempRepository, times(2)).findByOrderByDataOraPubblicazioneAsc(any(Pageable.class));
    }

    @Test
    @DisplayName("Should handle single page with records less than page size")
    void testReadSinglePartialPage() throws Exception {
        // Given: Single page with 10 records (less than pageSize=50)
        List<FrTemp> page = createFrTempList(10, 0);

        when(frTempRepository.findByOrderByDataOraPubblicazioneAsc(any(Pageable.class)))
            .thenReturn(new PageImpl<>(page))
            .thenReturn(Page.empty());

        // When: Read all
        int count = 0;
        while (reader.read() != null) {
            count++;
        }

        // Then: Should read all 10 records
        assertThat(count).isEqualTo(10);
    }

    @Test
    @DisplayName("Should initialize only once on first read")
    void testInitializeOnce() throws Exception {
        // Given
        List<FrTemp> page = createFrTempList(3, 0);
        when(frTempRepository.findByOrderByDataOraPubblicazioneAsc(any(Pageable.class)))
            .thenReturn(new PageImpl<>(page))
            .thenReturn(Page.empty());

        // When: Read multiple times
        reader.read(); // First read initializes
        reader.read();
        reader.read();
        reader.read(); // Should return null

        // Then: Repository called for page 0 and page 1
        verify(frTempRepository, times(2)).findByOrderByDataOraPubblicazioneAsc(any(Pageable.class));
    }

    @Test
    @DisplayName("Should read records in correct order")
    void testReadInOrder() throws Exception {
        // Given: Records with sequential IDs
        List<FrTemp> page = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            FrTemp frTemp = FrTemp.builder()
                .id((long) i)
                .codDominio("DOM" + i)
                .codFlusso("FDR-" + String.format("%03d", i))
                .idPsp("PSP001")
                .revisione(1L)
                .build();
            page.add(frTemp);
        }

        when(frTempRepository.findByOrderByDataOraPubblicazioneAsc(any(Pageable.class)))
            .thenReturn(new PageImpl<>(page))
            .thenReturn(Page.empty());

        // When: Read all
        List<FrTemp> results = new ArrayList<>();
        FrTemp record;
        while ((record = reader.read()) != null) {
            results.add(record);
        }

        // Then: Should maintain order
        assertThat(results).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(results.get(i).getId()).isEqualTo((long) i);
            assertThat(results.get(i).getCodFlusso()).isEqualTo("FDR-" + String.format("%03d", i));
        }
    }

    @Test
    @DisplayName("Should handle exactly pageSize records")
    void testReadExactlyPageSize() throws Exception {
        // Given: Exactly 50 records (pageSize)
        List<FrTemp> page = createFrTempList(50, 0);

        when(frTempRepository.findByOrderByDataOraPubblicazioneAsc(any(Pageable.class)))
            .thenReturn(new PageImpl<>(page))
            .thenReturn(Page.empty());

        // When: Read all
        int count = 0;
        while (reader.read() != null) {
            count++;
        }

        // Then: Should read all 50 records
        assertThat(count).isEqualTo(50);
    }

    @Test
    @DisplayName("Should handle large dataset with multiple pages")
    void testReadLargeDataset() throws Exception {
        // Given: 5 full pages (250 records)
        when(frTempRepository.findByOrderByDataOraPubblicazioneAsc(any(Pageable.class)))
            .thenAnswer(invocation -> {
                Pageable pageable = invocation.getArgument(0);
                int pageNumber = pageable.getPageNumber();
                if (pageNumber < 5) {
                    return new PageImpl<>(createFrTempList(50, pageNumber * 50));
                }
                return Page.empty();
            });

        // When: Read all
        int count = 0;
        while (reader.read() != null) {
            count++;
        }

        // Then: Should read all 250 records
        assertThat(count).isEqualTo(250);
    }

    private List<FrTemp> createFrTempList(int size, int startIndex) {
        List<FrTemp> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            FrTemp frTemp = FrTemp.builder()
                .id((long) (startIndex + i))
                .codDominio("12345678901")
                .codFlusso("FDR-" + String.format("%05d", startIndex + i))
                .idPsp("PSP001")
                .revisione(1L)
                .dataOraPubblicazione(Instant.now())
                .build();
            list.add(frTemp);
        }
        return list;
    }
}
