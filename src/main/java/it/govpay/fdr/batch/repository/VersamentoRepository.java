package it.govpay.fdr.batch.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import it.govpay.fdr.batch.entity.Versamento;
import it.govpay.fdr.batch.repository.specs.VersamentoSpecs;

@Repository
public interface VersamentoRepository extends JpaRepository<Versamento, Long>, JpaSpecificationExecutor<Versamento> {

	/**
     * Find Versamento by domain code and IUV (searches in both iuvVersamento and iuvPagamento)
     *
     * Logic: dominio.codDominio = :codDominio AND (iuvVersamento = :iuv OR iuvPagamento = :iuv)
     */
    default Optional<Versamento> findByDominioCodDominioAndIuvPagamento(String codDominio, String iuv) {
        return findOne(VersamentoSpecs.hasCodDominioAndIuv(codDominio, iuv));
    }

}
