package it.govpay.fdr.batch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity representing a FDR (Flusso di Rendicontazione)
 */
@Entity
@Table(name = "FR", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"cod_flusso", "cod_psp", "revision"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Fr {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_dominio", nullable = false)
    private Dominio dominio;

    @Column(name = "cod_flusso", nullable = false, length = 35)
    private String codFlusso;

    @Column(name = "cod_psp", nullable = false, length = 35)
    private String codPsp;

    @Column(name = "revision", nullable = false)
    private Long revision;

    @Column(name = "stato", length = 35)
    private String stato;

    @Column(name = "data_flusso")
    private Instant dataFlusso;

    @Column(name = "data_regolamento")
    private Instant dataRegolamento;

    @Column(name = "identificativo_regolamento", length = 35)
    private String identificativoRegolamento;

    @Column(name = "bic_riversamento", length = 35)
    private String bicRiversamento;

    @Column(name = "numero_pagamenti")
    private Long numeroPagamenti;

    @Column(name = "importo_totale_pagamenti", precision = 19, scale = 2)
    private BigDecimal importoTotalePagamenti;

    @Column(name = "cod_psp_mittente", length = 35)
    private String codPspMittente;

    @Column(name = "ragione_sociale_psp", length = 255)
    private String ragioneSocialePsp;

    @Column(name = "cod_intermediario_psp", length = 35)
    private String codIntermediarioPsp;

    @Column(name = "cod_canale", length = 35)
    private String codCanale;

    @Column(name = "data_pubblicazione")
    private Instant dataPubblicazione;

    @Column(name = "data_acquisizione")
    private Instant dataAcquisizione;

    @OneToMany(mappedBy = "fr", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Rendicontazione> rendicontazioni = new ArrayList<>();

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        dataAcquisizione = Instant.now();
    }

    public void addRendicontazione(Rendicontazione rendicontazione) {
        rendicontazioni.add(rendicontazione);
        rendicontazione.setFr(this);
    }
}
