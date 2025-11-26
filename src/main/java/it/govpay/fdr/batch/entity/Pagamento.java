package it.govpay.fdr.batch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_singolo_versamento")
    private SingoloVersamento singoloVersamento;

    @Column(name = "cod_dominio", length = 35)
    private String codDominio;

    @Column(name = "iuv", length = 35)
    private String iuv;
    
    @Column(name = "iur", length = 35)
    private String iur;

    @Column(name = "indice_dati")
    private Integer indiceDati;

    @Column(name = "importo_pagato")
    private Double importoPagato;
    
    @Column(name = "importo_revocato")
    private Double importoRevocato;
    
}
