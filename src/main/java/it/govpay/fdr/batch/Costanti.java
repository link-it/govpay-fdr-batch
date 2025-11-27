package it.govpay.fdr.batch;

import it.govpay.fdr.batch.entity.StatoFr;
import it.govpay.fdr.batch.entity.StatoRendicontazione;

public class Costanti {
	
	private Costanti() {
		// Costruttore privato per evitare istanziazione
	}
	
	public static final int PAYMENT_EXECUTED = 0;
	public static final int PAYMENT_REVOKED = 3;
	public static final int PAYMENT_STAND_IN = 4;
	public static final int PAYMENT_STAND_IN_NO_RPT = 8;
	public static final int PAYMENT_NO_RPT = 9;

	// Stati Rendicontazione - uso diretto degli enum
	public static final StatoRendicontazione RENDICONTAZIONE_STATO_OK = StatoRendicontazione.OK;
	public static final StatoRendicontazione RENDICONTAZIONE_STATO_ALTRO_INTERMEDIARIO = StatoRendicontazione.ALTRO_INTERMEDIARIO;
	public static final StatoRendicontazione RENDICONTAZIONE_STATO_ANOMALA = StatoRendicontazione.ANOMALA;

	// Stati Flusso/Fr - uso diretto degli enum
	public static final StatoFr FLUSSO_STATO_ACCETTATA = StatoFr.ACCETTATA;
	public static final StatoFr FLUSSO_STATO_ANOMALA = StatoFr.ANOMALA;

	// Operazioni FDR API (da fdr_organization.json)
	public static final String OPERATION_GET_ALL_PUBLISHED_FLOWS = "IOrganizationsController_getAllPublishedFlows";
	public static final String OPERATION_GET_SINGLE_PUBLISHED_FLOW = "IOrganizationsController_getSinglePublishedFlow";
	public static final String OPERATION_GET_PAYMENTS_FROM_PUBLISHED_FLOW = "IOrganizationsController_getPaymentsFromPublishedFlow";

	// Path delle operazioni FDR API (da fdr_organization.json)
	public static final String PATH_GET_ALL_PUBLISHED_FLOWS = "/organizations/{organizationId}/fdrs";
	public static final String PATH_GET_SINGLE_PUBLISHED_FLOW = "/organizations/{organizationId}/fdrs/{fdr}/revisions/{revision}/psps/{pspId}";
	public static final String PATH_GET_PAYMENTS_FROM_PUBLISHED_FLOW = "/organizations/{organizationId}/fdrs/{fdr}/revisions/{revision}/psps/{pspId}/payments";
}
