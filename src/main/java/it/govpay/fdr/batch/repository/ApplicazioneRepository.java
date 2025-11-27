package it.govpay.fdr.batch.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import it.govpay.fdr.batch.entity.Applicazione;

/**
 * Repository for Applicazione entity
 */
@Repository
public interface ApplicazioneRepository extends JpaRepository<Applicazione, Long> {

}
