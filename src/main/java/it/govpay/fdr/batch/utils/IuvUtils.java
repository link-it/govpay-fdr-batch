package it.govpay.fdr.batch.utils;

import java.math.BigInteger;

import it.govpay.fdr.batch.entity.Dominio;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IuvUtils {
	
	private IuvUtils() { /*static only */}
	
    public static boolean isIuvInterno(Dominio dominio, String iuv) {

        if (dominio == null) {
            // Se il dominio non e' censito, allora sicuramente non e' interno
        	log.debug("Dominio non censito, IUV:{} non interno", iuv);
            return false;
        }

        boolean isNumerico;

        try {
            new BigInteger(iuv);
            isNumerico = true;
        } catch (Exception e) {
            isNumerico = false;
        }

        log.debug( "Dominio:{}, AuxDigit:{}, Codice segregazione:{}", dominio.getCodDominio(), dominio.getAuxDigit(), dominio.getSegregationCode());
		log.debug( "IUV:{}, lunghezza:{} di tipo numerico: {}", iuv, iuv.length(), (isNumerico ? "SI" : "NO"));

		// AuxDigit 0: Ente monointermediato. 
		// Per i pagamenti di tipo 1 e 2, se non ho trovato il pagamento e sono arrivato qui, posso assumere che non e' interno.
		// Per i pagamenti di tipo 3, e' mio se e' di 15 cifre.
		// Quindi controllo solo se e' numerico e di 15 cifre.
		if(dominio.getAuxDigit() == 0 && isNumerico && iuv.length() == 15) {
			log.debug( "AuxDigit 0 -> EC Monointermediato, iuv numerico di lunghezza 15: e' interno.");
			return true;
		}
		
		// AuxDigit 1: Ente monointermediato. 
		// Per i pagamenti di tipo 1 e 2, se non ho trovato il pagamento e sono arrivato qui, posso assumere che non e' interno.
		// Per i pagamenti di tipo 3, e' mio se e' di 17 cifre.
		// Quindi controllo solo se e' numerico e di 17 cifre.
		if(dominio.getAuxDigit() == 1 && isNumerico && iuv.length() == 17) {
			log.debug( "AuxDigit 1 -> EC Monointermediato, iuv numerico di lunghezza 17: e' interno.");
			return true;
		}

		if(dominio.getAuxDigit() == 3) {
			// AuxDigit 3: Ente plurintermediato.
			// 
			// Gli IUV generati da GovPay sono nelle forme:
			// RF <check digit (2n)><codice segregazione (2n)><codice alfanumerico (max 19)>
			// <codice segregazione (2n)><IUV base (max 13n)><IUV check digit (2n)>

			// Pagamenti tipo 1 e 2 operati da GovPay
			if(iuv.startsWith("RF") && iuv.substring(4, 6).equals(String.format("%02d", dominio.getSegregationCode()))) {
				log.debug( "AuxDigit 3 -> EC Plurintermediato, iuv non numerico contenente il codice di segregazione: e' interno.");
				return true;
			}

			// Pagamenti tipo 3
			if(isNumerico && iuv.length() == 17 && iuv.startsWith(String.format("%02d", dominio.getSegregationCode()))) {
				log.debug( "AuxDigit 3 -> EC Plurintermediato, iuv numerico di lunghezza 17, inizia con il codice di segregazione: e' interno.");
				return true;
			}
		}
		
		log.debug( "IUV {} non interno.", iuv);
        return false;
    }
}
