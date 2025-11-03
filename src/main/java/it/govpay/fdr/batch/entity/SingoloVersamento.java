package it.govpay.fdr.batch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Entity representing a single payment position item
 */
@Entity
@Table(name = "SINGOLI_VERSAMENTI")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingoloVersamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "cod_dominio", length = 35)
    private String codDominio;

    @Column(name = "iuv", length = 35)
    private String iuv;

    @Column(name = "importo_singolo_versamento", precision = 19, scale = 2)
    private BigDecimal importoSingoloVersamento;

    @Column(name = "descrizione", length = 512)
    private String descrizione;

    @Version
    @Column(name = "version")
    private Long version;
}
