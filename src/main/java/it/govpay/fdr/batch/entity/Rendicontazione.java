package it.govpay.fdr.batch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_pagamento")
    private Pagamento pagamento;
    
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_singolo_versamento")
    private SingoloVersamento singoloVersamento;

    @Column(name = "iuv", nullable = false, length = 35)
    private String iuv;

    @Column(name = "iur", nullable = false, length = 35)
    private String iur;

    @Column(name = "indice_dati")
    private Integer indiceDati;

    @Column(name = "importo_pagato", nullable = false)
    private Double importoPagato;

    @Column(name = "esito")
    private Integer esito;

    @Column(name = "data")
    private LocalDateTime data;

    @Enumerated(EnumType.STRING)
    @Column(name = "stato", length = 35, nullable = false)
    private StatoRendicontazione stato;

    @Lob
    @Column(name = "anomalie")
    private String anomalie;
}
