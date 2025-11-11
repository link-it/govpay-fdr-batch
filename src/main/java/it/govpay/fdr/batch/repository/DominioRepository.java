package it.govpay.fdr.batch.repository;

import it.govpay.fdr.batch.entity.Dominio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DominioRepository extends JpaRepository<Dominio, String> {

	/**
     * Find domain by code
     */
    Optional<Dominio> findByCodDominio(String codDominio);

    /**
     * Find all enabled domains
     */
	@Query("SELECT d, MAX(f.dataOraPubblicazione) FROM Dominio d LEFT JOIN d.frList f WHERE d.scaricaFr = true GROUP BY d")
    List<Object[]> findDominioWithMaxDataOraPubblicazione();

}
