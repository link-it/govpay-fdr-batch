package it.govpay.fdr.batch.repository;

import it.govpay.fdr.batch.entity.Pagamento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PagamentoRepository extends JpaRepository<Pagamento, Long> {

    /**
     * Find payments by domain code, IUV
     */
    List<Pagamento> findAllByCodDominioAndIuv(String codDominio, String iuv);

    /**
     * Find payments by domain code, IUV and data index
     */
    List<Pagamento> findAllByCodDominioAndIuvAndIndiceDati(String codDominio, String iuv, Long indiceDati);

    /**
     * Find payments by domain code, IUV and IUR
     */
    List<Pagamento> findAllByCodDominioAndIuvAndIur(String codDominio, String iuv, String iur);

    /**
     * Find payments by domain code, IUV, IUR and data index
     */
    List<Pagamento> findAllByCodDominioAndIuvAndIurAndIndiceDati(String codDominio, String iuv, String iur, Long indiceDati);

}
