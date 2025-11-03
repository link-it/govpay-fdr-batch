package it.govpay.fdr.batch.repository;

import it.govpay.fdr.batch.entity.Fr;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FrRepository extends JpaRepository<Fr, Long> {

    /**
     * Find FDR by flow code, PSP code and revision
     */
    Optional<Fr> findByCodFlussoAndCodPspAndRevision(String codFlusso, String codPsp, Long revision);

    /**
     * Check if FDR already exists
     */
    boolean existsByCodFlussoAndCodPspAndRevision(String codFlusso, String codPsp, Long revision);
}
