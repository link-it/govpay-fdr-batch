package it.govpay.fdr.batch.dto;

import java.nio.file.Path;

/**
 * File della directory di acquisizione preso in carico da questo nodo.
 *
 * @param file          percorso del file dopo la rinomina di presa in carico
 * @param nomeOriginale nome con cui il file era stato depositato
 */
public record FdrClaimedFile(Path file, String nomeOriginale) {
}
