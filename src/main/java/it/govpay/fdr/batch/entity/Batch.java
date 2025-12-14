package it.govpay.fdr.batch.entity;

import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity representing a batch execution control record.
 * Used by GovPay to trigger manual batch executions.
 */
@Entity
@Table(name = "BATCH")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Batch {

    @Id
    @Column(name = "cod_batch", length = 255)
    private String codBatch;

    @Column(name = "nodo", length = 255)
    private String nodo;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "inizio")
    private Date inizio;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "aggiornamento")
    private Date aggiornamento;
}
