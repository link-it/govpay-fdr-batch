package it.govpay.fdr.batch.exception;

/**
 * Eccezione fatale che indica un errore non recuperabile (es. 401, 403).
 * Non deve essere ritentata ne' saltata dal batch: causa il fallimento dello step.
 */
public class FdrFatalException extends RuntimeException {

    public FdrFatalException(String message, Throwable cause) {
        super(message, cause);
    }

    public FdrFatalException(String message) {
        super(message);
    }
}
