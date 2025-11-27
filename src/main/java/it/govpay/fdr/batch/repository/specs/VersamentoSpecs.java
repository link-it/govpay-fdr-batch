package it.govpay.fdr.batch.repository.specs;

import org.springframework.data.jpa.domain.Specification;

import it.govpay.fdr.batch.entity.Dominio_;
import it.govpay.fdr.batch.entity.Versamento;
import it.govpay.fdr.batch.entity.Versamento_;
import jakarta.persistence.criteria.Predicate;

/**
 * JPA Specifications for Versamento entity queries.
 * Uses JPA Metamodel for type-safe queries.
 */
public class VersamentoSpecs {

	private VersamentoSpecs() {
		// Utility class - private constructor
	}

	/**
	 * Specification to find Versamento by domain code and IUV.
	 * Searches for IUV in both iuvVersamento and iuvPagamento fields.
	 *
	 * @param codDominio the domain code
	 * @param iuv the IUV to search for
	 * @return Specification with the query logic
	 */
	public static Specification<Versamento> hasCodDominioAndIuv(String codDominio, String iuv) {
		return (root, query, criteriaBuilder) -> {
			// dominio.codDominio = codDominio (using Metamodel)
			Predicate dominioCondition = criteriaBuilder.equal(
				root.get(Versamento_.dominio).get(Dominio_.codDominio), codDominio
			);

			// iuvVersamento = iuv OR iuvPagamento = iuv (using Metamodel)
			Predicate iuvCondition = criteriaBuilder.or(
				criteriaBuilder.equal(root.get(Versamento_.iuvVersamento), iuv),
				criteriaBuilder.equal(root.get(Versamento_.iuvPagamento), iuv)
			);

			// AND them together
			return criteriaBuilder.and(dominioCondition, iuvCondition);
		};
	}
}
