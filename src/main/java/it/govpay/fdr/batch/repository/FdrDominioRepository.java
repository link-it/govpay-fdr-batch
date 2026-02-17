package it.govpay.fdr.batch.repository;

import it.govpay.common.entity.DominioEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository locale per query FDR-specific su DominioEntity.
 * Le query base (findByCodDominio, etc.) sono fornite da govpay-common DominioRepository.
 */
@Repository
public interface FdrDominioRepository extends JpaRepository<DominioEntity, Long> {

    /**
     * Find all enabled domains (scaricaFr=true) with their max FR publication date.
     * Uses explicit JOIN with Fr table since DominioEntity doesn't have frList relationship.
     */
    @Query("SELECT d, MAX(f.dataOraPubblicazione) FROM DominioEntity d LEFT JOIN Fr f ON f.dominio = d WHERE d.scaricaFr = true GROUP BY d")
    List<Object[]> findDominioWithMaxDataOraPubblicazione();

}
