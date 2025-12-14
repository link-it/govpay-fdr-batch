package it.govpay.fdr.batch.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import it.govpay.fdr.batch.entity.Batch;

/**
 * Repository for Batch entity.
 * Used to check for manual batch activation requests from GovPay.
 */
@Repository
public interface BatchRepository extends JpaRepository<Batch, String> {

    /**
     * Find batch by code
     */
    Optional<Batch> findByCodBatch(String codBatch);
}
