package it.govpay.fdr.batch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity representing a domain (creditor institution)
 */
@Entity
@Table(name = "APPLICAZIONI")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Applicazione {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "cod_applicazione", nullable = false, unique = true, length = 35)
    private String codApplicazione;
}
