package it.govpay.fdr.batch.gde.utils;

import java.util.Base64;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.govpay.fdr.batch.Costanti;
import it.govpay.gde.client.model.DettaglioRisposta;
import it.govpay.gde.client.model.NuovoEvento;

public class GdeUtils {

	private GdeUtils () {}

	public static void serializzaPayload(ObjectMapper objectMapper, NuovoEvento nuovoEvento, ResponseEntity<?> response, RestClientException e) {
		DettaglioRisposta parametriRisposta = nuovoEvento.getParametriRisposta();

		if(parametriRisposta != null) {
			if(e != null) {
				if (e instanceof HttpStatusCodeException httpStatusCodeException) {
					parametriRisposta.setPayload(Base64.getEncoder().encodeToString(httpStatusCodeException.getResponseBodyAsByteArray()));
				} else {
					parametriRisposta.setPayload(Base64.getEncoder().encodeToString(e.getMessage().getBytes()));
				}
			} else if(response != null) {
				parametriRisposta.setPayload(Base64.getEncoder().encodeToString(writeValueAsString(objectMapper, response.getBody()).getBytes()));
			} 
		}
	}

	public static String writeValueAsString(ObjectMapper objectMapper, Object obj) {
		try {
			return objectMapper.writeValueAsString(obj);
		} catch (JsonProcessingException e) {
			return Costanti.MSG_PAYLOAD_NON_SERIALIZZABILE;
		}
	}
}
