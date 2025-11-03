package it.govpay.fdr.batch.repository;

import it.govpay.fdr.batch.entity.FrTemp;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FrTempRepository extends JpaRepository<FrTemp, Long> {

    /**
     * Delete all records from FR_TEMP
     */
    @Modifying
    @Query("DELETE FROM FrTemp")
    void deleteAllRecords();

    /**
     * Find unprocessed records ordered by publication date
     */
    Page<FrTemp> findByProcessatoFalseOrderByDataPubblicazioneAsc(Pageable pageable);

    /**
     * Find unprocessed records by domain code
     */
    List<FrTemp> findByCodDominioAndProcessatoFalseOrderByDataPubblicazioneAsc(String codDominio);

    /**
     * Check if FDR already exists in temporary table
     */
    boolean existsByCodDominioAndCodFlussoAndCodPspAndRevision(
        String codDominio, String codFlusso, String codPsp, Long revision
    );
}
