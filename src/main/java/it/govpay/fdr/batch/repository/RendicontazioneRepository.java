package it.govpay.fdr.batch.repository;

import it.govpay.fdr.batch.entity.Rendicontazione;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RendicontazioneRepository extends JpaRepository<Rendicontazione, Long> {
}
