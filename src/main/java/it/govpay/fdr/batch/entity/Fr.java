package it.govpay.fdr.batch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Column(name = "cod_psp", nullable = false, length = 35)
    private String codPsp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_dominio", nullable = false)
    private Dominio dominio;

    @Column(name = "cod_dominio", nullable = false)
    private String codDominio;

    @Column(name = "cod_flusso", nullable = false, length = 35)
    private String codFlusso;

    @Enumerated(EnumType.STRING)
    @Column(name = "stato", length = 35)
    private StatoFr stato;

    @Column(name = "descrizione_stato", columnDefinition = "TEXT")
    private String descrizioneStato;

    @Column(name = "iur", length = 35)
    private String iur;

    @Column(name = "data_ora_flusso")
    private Instant dataOraFlusso;

    @Column(name = "data_regolamento")
    private Instant dataRegolamento;

    @Column(name = "data_acquisizione")
    private Instant dataAcquisizione;

    @Column(name = "numero_pagamenti")
    private Long numeroPagamenti;

    @Column(name = "importo_totale_pagamenti")
    private Double importoTotalePagamenti;

    @Column(name = "cod_bic_riversamento", length = 35)
    private String codBicRiversamento;

    @Column(name = "ragione_sociale_psp", length = 70)
    private String ragioneSocialePsp;

    @Column(name = "ragione_sociale_dominio", length = 70)
    private String ragioneSocialeDominio;

    @Column(name = "data_ora_pubblicazione")
    private Instant dataOraPubblicazione;

    @Column(name = "data_ora_aggiornamento")
    private Instant dataOraAggiornamento;

    @Column(name = "revisione", nullable = false)
    private Long revisione;

    @Column(name = "obsoleto", nullable = false)
    @Builder.Default
    private Boolean obsoleto = false;

    @Column(name = "id_incasso")
    private Long idIncasso;

    @OneToMany(mappedBy = "fr", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Rendicontazione> rendicontazioni = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        dataAcquisizione = Instant.now();
    }

    public void addRendicontazione(Rendicontazione rendicontazione) {
        rendicontazioni.add(rendicontazione);
        rendicontazione.setFr(this);
    }
}
