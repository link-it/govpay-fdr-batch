package it.govpay.fdr.batch.repository;

import it.govpay.fdr.batch.entity.Fr;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FrRepository extends JpaRepository<Fr, Long> {

    /**
     * Find FDR by flow code, PSP id and revision
     */
    Optional<Fr> findByCodFlussoAndCodPspAndRevisione(String codFlusso, String codPsp, Long revision);

    /**
     * Check if FDR already exists (without domain filter)
     */
    boolean existsByCodFlussoAndCodPspAndRevisione(String codFlusso, String codPsp, Long revision);

    /**
     * Check if FDR already exists for specific domain
     */
    boolean existsByCodDominioAndCodFlussoAndCodPspAndRevisione(
        String codDominio, String codFlusso, String codPsp, Long revision);
}
