package it.govpay.fdr.batch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Entity representing a domain (creditor institution)
 */
@Entity
@Table(name = "DOMINI")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Dominio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "cod_dominio", nullable = false, unique = true, length = 35)
    private String codDominio;

    @Column(name = "ragione_sociale", length = 255)
    private String ragioneSociale;

    @Column(name = "abilitato", nullable = false)
    private Boolean abilitato = true;

    @Column(name = "data_ultima_acquisizione")
    private Instant dataUltimaAcquisizione;

    @Version
    @Column(name = "version")
    private Long version;
}
