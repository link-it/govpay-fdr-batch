package it.govpay.fdr.batch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Entity representing a single payment in a FDR
 */
@Entity
@Table(name = "RENDICONTAZIONI")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Rendicontazione {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_fr", nullable = false)
    private Fr fr;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_pagamento")
    private Pagamento pagamento;

    @Column(name = "iuv", nullable = false, length = 35)
    private String iuv;

    @Column(name = "iur", nullable = false, length = 35)
    private String iur;

    @Column(name = "indice_dati")
    private Long indiceDati;

    @Column(name = "importo_pagamento", nullable = false, precision = 19, scale = 2)
    private BigDecimal importoPagamento;

    @Column(name = "esito", length = 35)
    private String esito;

    @Column(name = "data")
    private Instant data;

    @Column(name = "stato", length = 35)
    private String stato;

    @Column(name = "anomalie", length = 512)
    private String anomalie;

    @Version
    @Column(name = "version")
    private Long version;
}
