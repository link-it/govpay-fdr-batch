package it.govpay.fdr.batch.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import it.govpay.fdr.batch.entity.Versamento;

@Repository
public interface VersamentoRepository extends JpaRepository<Versamento, Long> {

	/**
     * Find Versamento by domain code and IUV
     */
    Optional<Versamento> findByDominioCodDominioAndIuvPagamento(String codDominio, String iuv);

}
