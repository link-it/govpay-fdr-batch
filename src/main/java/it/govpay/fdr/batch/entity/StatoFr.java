package it.govpay.fdr.batch.entity;

/**
 * Enum representing the possible states of a FDR (Flusso di Rendicontazione).
 */
public enum StatoFr {

    /**
     * FDR accettato
     */
    ACCETTATA,

    /**
     * FDR con anomalie
     */
    ANOMALA,

    /**
     * FDR rifiutato - per retrocompatibilità v2.2
     */
    RIFIUTATA;
}
