package it.govpay.fdr.batch.step2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import it.govpay.common.entity.DominioEntity;
import it.govpay.fdr.batch.dto.DominioProcessingContext;
import it.govpay.fdr.batch.repository.FdrDominioRepository;

/**
 * Unit tests for FdrHeadersReader
 */
@ExtendWith(MockitoExtension.class)
class FdrHeadersReaderTest {

    @Mock
    private FdrDominioRepository fdrDominioRepository;

    private FdrHeadersReader reader;

    @BeforeEach
    void setUp() {
        // Reset static queue before each test
        FdrHeadersReader.resetQueue();
        reader = new FdrHeadersReader(fdrDominioRepository);
    }

    @Test
    @DisplayName("Should read all domains and return DominioProcessingContext")
    void testReadMultipleDomains() throws Exception {
        // Given: 3 domains with last publication dates
        List<Object[]> dominioInfos = new ArrayList<>();

        DominioEntity dominio1 = DominioEntity.builder().id(1L).codDominio("12345678901").build();
        LocalDateTime localDateTime1 = LocalDateTime.of(2025, 1, 27, 10, 0, 0);
        dominioInfos.add(new Object[]{dominio1, localDateTime1});

        DominioEntity dominio2 = DominioEntity.builder().id(2L).codDominio("12345678902").build();
        LocalDateTime localDateTime2 = LocalDateTime.of(2025, 1, 27, 11, 0, 0);
        dominioInfos.add(new Object[]{dominio2, localDateTime2});

        DominioEntity dominio3 = DominioEntity.builder().id(3L).codDominio("12345678903").build();
        LocalDateTime localDateTime3 = null; // No previous acquisition
        dominioInfos.add(new Object[]{dominio3, localDateTime3});

        when(fdrDominioRepository.findDominioWithMaxDataOraPubblicazione()).thenReturn(dominioInfos);

        // When: Read all domains
        DominioProcessingContext ctx1 = reader.read();
        DominioProcessingContext ctx2 = reader.read();
        DominioProcessingContext ctx3 = reader.read();
        DominioProcessingContext ctx4 = reader.read(); // Should be null

        // Then: Should return all 3 domains then null
        assertThat(ctx1).isNotNull();
        assertThat(ctx1.getDominioId()).isEqualTo(1L);
        assertThat(ctx1.getCodDominio()).isEqualTo("12345678901");
        assertThat(ctx1.getLastPublicationDate()).isEqualTo(localDateTime1);

        assertThat(ctx2).isNotNull();
        assertThat(ctx2.getDominioId()).isEqualTo(2L);
        assertThat(ctx2.getCodDominio()).isEqualTo("12345678902");
        assertThat(ctx2.getLastPublicationDate()).isEqualTo(localDateTime2);

        assertThat(ctx3).isNotNull();
        assertThat(ctx3.getDominioId()).isEqualTo(3L);
        assertThat(ctx3.getCodDominio()).isEqualTo("12345678903");
        assertThat(ctx3.getLastPublicationDate()).isNull();

        assertThat(ctx4).isNull(); // End of data

        // Verify repository was called only once (on first read)
        verify(fdrDominioRepository, times(1)).findDominioWithMaxDataOraPubblicazione();
    }

    @Test
    @DisplayName("Should return null when no domains found")
    void testReadNoDomains() throws Exception {
        // Given: No domains
        when(fdrDominioRepository.findDominioWithMaxDataOraPubblicazione()).thenReturn(new ArrayList<>());

        // When: Read
        DominioProcessingContext result = reader.read();

        // Then: Should return null immediately
        assertThat(result).isNull();
        verify(fdrDominioRepository, times(1)).findDominioWithMaxDataOraPubblicazione();
    }

    @Test
    @DisplayName("Should handle single domain")
    void testReadSingleDomain() throws Exception {
        // Given: Single domain
        List<Object[]> dominioInfos = new ArrayList<>();
        DominioEntity dominio = DominioEntity.builder().id(1L).codDominio("12345678901").build();
        LocalDateTime localDateTime = LocalDateTime.of(2025, 1, 27, 10, 0, 0);
        dominioInfos.add(new Object[]{dominio, localDateTime});

        when(fdrDominioRepository.findDominioWithMaxDataOraPubblicazione()).thenReturn(dominioInfos);

        // When: Read twice
        DominioProcessingContext ctx1 = reader.read();
        DominioProcessingContext ctx2 = reader.read();

        // Then: First should return domain, second should be null
        assertThat(ctx1).isNotNull();
        assertThat(ctx1.getCodDominio()).isEqualTo("12345678901");
        assertThat(ctx2).isNull();
    }

    @Test
    @DisplayName("Should initialize only once on first read")
    void testInitializeOnce() throws Exception {
        // Given
        List<Object[]> dominioInfos = new ArrayList<>();
        DominioEntity dominio = DominioEntity.builder().id(1L).codDominio("12345678901").build();
        dominioInfos.add(new Object[]{dominio, LocalDateTime.now()});

        when(fdrDominioRepository.findDominioWithMaxDataOraPubblicazione()).thenReturn(dominioInfos);

        // When: Read multiple times
        reader.read();
        reader.read();
        reader.read();

        // Then: Repository should be called only once
        verify(fdrDominioRepository, times(1)).findDominioWithMaxDataOraPubblicazione();
    }
}
