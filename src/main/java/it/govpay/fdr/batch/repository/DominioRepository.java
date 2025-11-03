package it.govpay.fdr.batch.repository;

import it.govpay.fdr.batch.entity.Dominio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DominioRepository extends JpaRepository<Dominio, Long> {

    /**
     * Find domain by code
     */
    Optional<Dominio> findByCodDominio(String codDominio);

    /**
     * Find all enabled domains
     */
    List<Dominio> findByAbilitatoTrue();
}
