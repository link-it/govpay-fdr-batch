package it.govpay.fdr.batch.entity;

/**
 * Enum representing the possible states of a Rendicontazione (Payment reconciliation).
 */
public enum StatoRendicontazione {

    /**
     * Rendicontazione elaborata correttamente
     */
    OK,

    /**
     * Rendicontazione con anomalie
     */
    ANOMALA,

    /**
     * Rendicontazione di altro intermediario
     */
    ALTRO_INTERMEDIARIO;
}
