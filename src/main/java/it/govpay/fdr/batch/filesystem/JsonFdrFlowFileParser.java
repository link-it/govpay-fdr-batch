package it.govpay.fdr.batch.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.govpay.fdr.batch.config.FdrApiClientConfig;
import it.govpay.fdr.batch.dto.FdrFlowFile;

/**
 * Lettura dei file JSON nel formato della response FDR di pagoPA (metadati del
 * flusso con i pagamenti inline).
 * <p>
 * E' il parser di ripiego: gestisce ogni file non rivendicato da un parser piu'
 * specifico, cosi' che un'estensione configurata diversa da {@code .json}
 * continui a essere letta come JSON, come prima dell'introduzione dell'XML.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class JsonFdrFlowFileParser implements FdrFlowFileParser {

    private final ObjectMapper jsonMapper;

    public JsonFdrFlowFileParser(FdrApiClientConfig fdrApiClientConfig) {
        // Stesso mapper usato verso le API pagoPA: i file hanno lo stesso formato date
        // (epoch con nanosecondi, millisecondi a lunghezza variabile, date senza offset)
        this.jsonMapper = fdrApiClientConfig.createPagoPAObjectMapper();
    }

    @Override
    public boolean supporta(String nomeFile) {
        return true;
    }

    @Override
    public FdrFlowFile leggi(Path file) throws IOException {
        return jsonMapper.readValue(Files.readString(file), FdrFlowFile.class);
    }

    @Override
    public String formato() {
        return "JSON";
    }
}
