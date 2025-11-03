package it.govpay.fdr.batch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Entity representing a payment
 */
@Entity
@Table(name = "PAGAMENTI")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Pagamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_singolo_versamento")
    private SingoloVersamento singoloVersamento;

    @Column(name = "cod_dominio", length = 35)
    private String codDominio;

    @Column(name = "iuv", length = 35)
    private String iuv;

    @Column(name = "indice_dati")
    private Long indiceDati;

    @Column(name = "importo_pagato", precision = 19, scale = 2)
    private BigDecimal importoPagato;

    @Column(name = "data_acquisizione")
    private Instant dataAcquisizione;

    @Column(name = "iur", length = 35)
    private String iur;

    @Column(name = "data_pagamento")
    private Instant dataPagamento;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        dataAcquisizione = Instant.now();
    }
}
