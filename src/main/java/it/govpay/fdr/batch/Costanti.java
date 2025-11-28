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

	// Pattern date per serializzazione/deserializzazione JSON
	// Pattern con millisecondi variabili (1-9 cifre) per deserializzazione sicura da pagoPA
	public static final String PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI = "yyyy-MM-dd'T'HH:mm:ss[.[SSSSSSSSS][SSSSSSSS][SSSSSSS][SSSSSS][SSSSS][SSSS][SSS][SS][S]]";
	public static final String PATTERN_YYYY_MM_DD_T_HH_MM_SS_MILLIS_VARIABILI_XXX = "yyyy-MM-dd'T'HH:mm:ss[.SSSSSSSSS][.SSSSSSSS][.SSSSSSS][.SSSSSS][.SSSSS][.SSSS][.SSS][.SS][.S]XXX";

	// Pattern per serializzazione date al GDE (3 cifre millisecondi con timezone)
	public static final String PATTERN_TIMESTAMP_3_YYYY_MM_DD_T_HH_MM_SS_SSSXXX = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

	// Pattern per serializzazione date alle API esterne (3 cifre millisecondi senza timezone)
	public static final String PATTERN_DATA_JSON_YYYY_MM_DD_T_HH_MM_SS_SSS = "yyyy-MM-dd'T'HH:mm:ss.SSS";

	// Job parameters per gestione multi-nodo
	public static final String GOVPAY_BATCH_JOB_ID = "JobID";
	public static final String GOVPAY_BATCH_JOB_PARAMETER_WHEN = "When";
	public static final String GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID = "ClusterID";

	// Nome job FDR acquisition
	public static final String FDR_ACQUISITION_JOB_NAME = "fdrAcquisitionJob";
}
