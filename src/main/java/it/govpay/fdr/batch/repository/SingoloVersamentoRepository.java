package it.govpay.fdr.batch.repository;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import it.govpay.fdr.batch.entity.SingoloVersamento;

@Repository
public interface SingoloVersamentoRepository extends JpaRepository<SingoloVersamento, Long> {
	public Set<SingoloVersamento> findAllByVersamentoId(Long idVersamento);
}
