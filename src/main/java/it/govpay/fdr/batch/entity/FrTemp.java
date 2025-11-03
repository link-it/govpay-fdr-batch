package it.govpay.fdr.batch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Temporary entity for storing FDR headers during batch processing
 */
@Entity
@Table(name = "FR_TEMP")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FrTemp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "cod_dominio", nullable = false, length = 35)
    private String codDominio;

    @Column(name = "cod_flusso", nullable = false, length = 35)
    private String codFlusso;

    @Column(name = "cod_psp", nullable = false, length = 35)
    private String codPsp;

    @Column(name = "revision", nullable = false)
    private Long revision;

    @Column(name = "data_flusso")
    private Instant dataFlusso;

    @Column(name = "data_pubblicazione")
    private Instant dataPubblicazione;

    @Column(name = "processato", nullable = false)
    private Boolean processato = false;

    @Column(name = "data_inserimento")
    private Instant dataInserimento;

    @PrePersist
    protected void onCreate() {
        dataInserimento = Instant.now();
    }
}
