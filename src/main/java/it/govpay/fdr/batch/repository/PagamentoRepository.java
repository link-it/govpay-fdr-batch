package it.govpay.fdr.batch.repository;

import it.govpay.fdr.batch.entity.Pagamento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PagamentoRepository extends JpaRepository<Pagamento, Long> {

    /**
     * Find payment by domain code, IUV and data index
     */
    Optional<Pagamento> findByCodDominioAndIuvAndIndiceDati(String codDominio, String iuv, Long indiceDati);
}
