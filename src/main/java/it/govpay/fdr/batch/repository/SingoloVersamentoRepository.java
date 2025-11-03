package it.govpay.fdr.batch.repository;

import it.govpay.fdr.batch.entity.SingoloVersamento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SingoloVersamentoRepository extends JpaRepository<SingoloVersamento, Long> {

    /**
     * Find single payment position by domain code and IUV
     */
    Optional<SingoloVersamento> findByCodDominioAndIuv(String codDominio, String iuv);
}
