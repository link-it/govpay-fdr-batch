package it.govpay.fdr.batch.repository;

import it.govpay.fdr.batch.entity.Fr;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Marca come obsoleti tutti i flussi con la stessa chiave (codDominio, codFlusso, codPsp)
     * che non sono già obsoleti.
     * @return il numero di record aggiornati
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Fr f SET f.obsoleto = true WHERE f.codDominio = :codDominio AND f.codFlusso = :codFlusso AND f.codPsp = :codPsp AND f.obsoleto = false")
    int marcaObsoleti(@Param("codDominio") String codDominio, @Param("codFlusso") String codFlusso, @Param("codPsp") String codPsp);
}
