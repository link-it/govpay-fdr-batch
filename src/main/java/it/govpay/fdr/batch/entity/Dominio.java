package it.govpay.fdr.batch.entity;

import java.util.List;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Column(name = "scarica_fr", nullable = false)
    @Builder.Default
    private Boolean scaricaFr = true;

    @Column(name = "aux_digit")
    private Integer auxDigit;

    @Column(name = "segregation_code")
    private Integer segregationCode;

    @OneToMany(mappedBy = "dominio")
    private List<Fr> frList;

}
