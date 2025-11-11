package it.govpay.fdr.batch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
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

    @Column(name = "id_psp", nullable = false, length = 35)
    private String idPsp;

    @Column(name = "cod_psp", nullable = false, length = 35)
    private String codPsp;

    @Column(name = "cod_dominio", nullable = false, length = 35)
    private String codDominio;

    @Column(name = "cod_flusso", nullable = false, length = 35)
    private String codFlusso;

    @Column(name = "iur", length = 35)
    private String iur;

    @Column(name = "data_ora_flusso")
    private Instant dataOraFlusso;

    @Column(name = "data_regolamento")
    private Instant dataRegolamento;

    @Column(name = "data_ora_aggiornamento")
    private Instant dataOraAggiornamento;

    @Column(name = "stato", length = 35)
    private String stato;

    @Column(name = "numero_pagamenti")
    private Long numeroPagamenti;

    @Column(name = "importo_totale_pagamenti", precision = 19, scale = 2)
    private BigDecimal importoTotalePagamenti;

    @Column(name = "cod_bic_riversamento", length = 35)
    private String codBicRiversamento;

    @Column(name = "ragione_sociale_psp", length = 255)
    private String ragioneSocialePsp;

    @Column(name = "ragione_sociale_dominio", length = 255)
    private String ragioneSocialeDominio;

    @Column(name = "data_ora_pubblicazione")
    private Instant dataOraPubblicazione;

    @Column(name = "revisione", nullable = false)
    private Long revisione;

}
