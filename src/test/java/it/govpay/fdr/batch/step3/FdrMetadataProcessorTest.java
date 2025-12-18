package it.govpay.fdr.batch.step3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.client.RestClientException;

import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.service.FdrApiService;
import it.govpay.fdr.batch.step3.FdrMetadataProcessor.FdrCompleteData;
import it.govpay.fdr.client.model.Receiver;
import it.govpay.fdr.client.model.ReportingFlowStatusEnum;
import it.govpay.fdr.client.model.Sender;
import it.govpay.fdr.client.model.SingleFlowResponse;

/**
 * Test per FdrMetadataProcessor
 */
@DisplayName("FdrMetadataProcessor Tests")
class FdrMetadataProcessorTest {

    @Mock
    private FdrApiService fdrApiService;
    
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Rome");

    private FdrMetadataProcessor processor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        processor = new FdrMetadataProcessor(fdrApiService, ZONE_ID);
    }

    private FrTemp createFrTemp() {
        return FrTemp.builder()
            .id(1L)
            .codDominio("12345678901")
            .codFlusso("FDR-001")
            .revisione(1L)
            .idPsp("PSP_12345")
            .codPsp("AGID_01")
            .build();
    }

    private SingleFlowResponse createCompleteFlowResponse() {
        SingleFlowResponse response = new SingleFlowResponse();

        Sender sender = new Sender();
        sender.setPspId("AGID_01");
        sender.setPspName("PSP Test");
        sender.setPspBrokerId("INT_01");
        sender.setChannelId("CHAN_01");
        response.setSender(sender);

        Receiver receiver = new Receiver();
        receiver.setOrganizationName("EC Test");
        response.setReceiver(receiver);

        response.setRegulation("IUR-123456");
        response.setFdrDate(OffsetDateTime.now());
        response.setRegulationDate(LocalDate.now());
        response.setTotPayments(100L);
        response.setSumPayments(5000.50);
        response.setBicCodePouringBank("BICCODE123");
        response.setPublished(OffsetDateTime.now());
        response.setUpdated(OffsetDateTime.now());
        response.setStatus(ReportingFlowStatusEnum.PUBLISHED);

        return response;
    }

    @Test
    @DisplayName("Test successful processing with complete data")
    void testProcessSuccessWithCompleteData() throws Exception {
        FrTemp frTemp = createFrTemp();
        SingleFlowResponse flowResponse = createCompleteFlowResponse();

        when(fdrApiService.getSinglePublishedFlow(
            anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(flowResponse);

        FdrCompleteData result = processor.process(frTemp);

        assertNotNull(result);
        assertEquals(1L, result.getFrTempId());
        assertEquals("AGID_01", result.getCodPsp());
        assertEquals("12345678901", result.getCodDominio());
        assertEquals("FDR-001", result.getCodFlusso());
        assertEquals("IUR-123456", result.getIur());
        assertEquals(100L, result.getNumeroPagamenti());
        assertEquals(5000.50, result.getImportoTotalePagamenti());
        assertEquals("BICCODE123", result.getCodBicRiversamento());
        assertEquals("PSP Test", result.getRagioneSocialePsp());
        assertEquals("EC Test", result.getRagioneSocialeDominio());
        assertEquals("INT_01", result.getCodIntermediarioPsp());
        assertEquals("CHAN_01", result.getCodCanale());
        assertEquals(1L, result.getRevisione());
        assertEquals("PUBLISHED", result.getStato());

        verify(fdrApiService).getSinglePublishedFlow("12345678901", "FDR-001", 1L, "PSP_12345");
    }

    @Test
    @DisplayName("Test processing with null sender")
    void testProcessWithNullSender() throws Exception {
        FrTemp frTemp = createFrTemp();
        SingleFlowResponse flowResponse = new SingleFlowResponse();
        flowResponse.setSender(null);
        flowResponse.setRegulation("IUR-123");
        flowResponse.setTotPayments(10L);
        flowResponse.setSumPayments(100.0);

        when(fdrApiService.getSinglePublishedFlow(anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(flowResponse);

        FdrCompleteData result = processor.process(frTemp);

        assertNotNull(result);
        assertNull(result.getCodPsp());
        assertNull(result.getCodPspMittente());
        assertNull(result.getRagioneSocialePsp());
        assertNull(result.getCodIntermediarioPsp());
        assertNull(result.getCodCanale());
    }

    @Test
    @DisplayName("Test processing with null receiver")
    void testProcessWithNullReceiver() throws Exception {
        FrTemp frTemp = createFrTemp();
        SingleFlowResponse flowResponse = new SingleFlowResponse();
        flowResponse.setReceiver(null);
        flowResponse.setRegulation("IUR-123");
        flowResponse.setTotPayments(10L);
        flowResponse.setSumPayments(100.0);

        when(fdrApiService.getSinglePublishedFlow(anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(flowResponse);

        FdrCompleteData result = processor.process(frTemp);

        assertNotNull(result);
        assertNull(result.getRagioneSocialeDominio());
    }

    @Test
    @DisplayName("Test processing with null status")
    void testProcessWithNullStatus() throws Exception {
        FrTemp frTemp = createFrTemp();
        SingleFlowResponse flowResponse = new SingleFlowResponse();
        flowResponse.setStatus(null);
        flowResponse.setRegulation("IUR-123");
        flowResponse.setTotPayments(10L);
        flowResponse.setSumPayments(100.0);

        when(fdrApiService.getSinglePublishedFlow(anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(flowResponse);

        FdrCompleteData result = processor.process(frTemp);

        assertNotNull(result);
        assertNull(result.getStato());
    }

    @Test
    @DisplayName("Test processing with null dates")
    void testProcessWithNullDates() throws Exception {
        FrTemp frTemp = createFrTemp();
        SingleFlowResponse flowResponse = new SingleFlowResponse();
        flowResponse.setFdrDate(null);
        flowResponse.setRegulationDate(null);
        flowResponse.setPublished(null);
        flowResponse.setUpdated(null);
        flowResponse.setRegulation("IUR-123");
        flowResponse.setTotPayments(10L);
        flowResponse.setSumPayments(100.0);

        when(fdrApiService.getSinglePublishedFlow(anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(flowResponse);

        FdrCompleteData result = processor.process(frTemp);

        assertNotNull(result);
        assertNull(result.getDataOraFlusso());
        assertNull(result.getDataRegolamento());
        assertNull(result.getDataOraPubblicazione());
        assertNull(result.getDataOraAggiornamento());
    }

    @Test
    @DisplayName("Test processing throws RestClientException")
    void testProcessThrowsRestClientException() throws Exception {
        FrTemp frTemp = createFrTemp();

        when(fdrApiService.getSinglePublishedFlow(anyString(), anyString(), anyLong(), anyString()))
            .thenThrow(new RestClientException("API error"));

        assertThrows(RestClientException.class, () -> processor.process(frTemp));

        verify(fdrApiService).getSinglePublishedFlow("12345678901", "FDR-001", 1L, "PSP_12345");
    }

    @Test
    @DisplayName("Test processing with OffsetDateTime conversion")
    void testOffsetDateTimeConversion() throws Exception {
        FrTemp frTemp = createFrTemp();
        SingleFlowResponse flowResponse = new SingleFlowResponse();

        OffsetDateTime testDate = OffsetDateTime.of(2025, 12, 4, 10, 30, 0, 0, ZONE_ID.getRules().getOffset(LocalDateTime.now()));
        flowResponse.setFdrDate(testDate);
        flowResponse.setPublished(testDate);
        flowResponse.setUpdated(testDate);
        flowResponse.setRegulation("IUR-123");
        flowResponse.setTotPayments(10L);
        flowResponse.setSumPayments(100.0);

        when(fdrApiService.getSinglePublishedFlow(anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(flowResponse);

        FdrCompleteData result = processor.process(frTemp);

        assertNotNull(result);
        assertEquals(testDate.toLocalDateTime(), result.getDataOraFlusso());
        assertEquals(testDate.toLocalDateTime(), result.getDataOraPubblicazione());
        assertEquals(testDate.toLocalDateTime(), result.getDataOraAggiornamento());
    }

    @Test
    @DisplayName("Test processing with LocalDate conversion")
    void testLocalDateConversion() throws Exception {
        FrTemp frTemp = createFrTemp();
        SingleFlowResponse flowResponse = new SingleFlowResponse();

        LocalDate testDate = LocalDate.of(2025, 12, 4);
        flowResponse.setRegulationDate(testDate);
        flowResponse.setRegulation("IUR-123");
        flowResponse.setTotPayments(10L);
        flowResponse.setSumPayments(100.0);

        when(fdrApiService.getSinglePublishedFlow(anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(flowResponse);

        FdrCompleteData result = processor.process(frTemp);

        assertNotNull(result);
        LocalDateTime expected = testDate.atStartOfDay(ZoneOffset.UTC).toLocalDateTime();
        assertEquals(expected, result.getDataRegolamento());
    }

    @Test
    @DisplayName("Test FdrCompleteData builder")
    void testFdrCompleteDataBuilder() {
    	LocalDateTime now = LocalDateTime.now();

        FdrCompleteData data = FdrCompleteData.builder()
            .frTempId(1L)
            .codPsp("PSP_01")
            .codDominio("12345678901")
            .codFlusso("FDR-001")
            .iur("IUR-123")
            .dataOraFlusso(now)
            .dataRegolamento(now)
            .numeroPagamenti(100L)
            .importoTotalePagamenti(5000.0)
            .codBicRiversamento("BIC123")
            .codPspMittente("PSP_01")
            .ragioneSocialePsp("PSP Name")
            .ragioneSocialeDominio("EC Name")
            .codIntermediarioPsp("INT_01")
            .codCanale("CHAN_01")
            .dataOraPubblicazione(now)
            .dataOraAggiornamento(now)
            .revisione(1L)
            .stato("PUBLISHED")
            .build();

        assertNotNull(data);
        assertEquals(1L, data.getFrTempId());
        assertEquals("PSP_01", data.getCodPsp());
        assertEquals("12345678901", data.getCodDominio());
        assertEquals("FDR-001", data.getCodFlusso());
        assertEquals("IUR-123", data.getIur());
        assertEquals(now, data.getDataOraFlusso());
        assertEquals(now, data.getDataRegolamento());
        assertEquals(100L, data.getNumeroPagamenti());
        assertEquals(5000.0, data.getImportoTotalePagamenti());
        assertEquals("BIC123", data.getCodBicRiversamento());
        assertEquals("PSP_01", data.getCodPspMittente());
        assertEquals("PSP Name", data.getRagioneSocialePsp());
        assertEquals("EC Name", data.getRagioneSocialeDominio());
        assertEquals("INT_01", data.getCodIntermediarioPsp());
        assertEquals("CHAN_01", data.getCodCanale());
        assertEquals(now, data.getDataOraPubblicazione());
        assertEquals(now, data.getDataOraAggiornamento());
        assertEquals(1L, data.getRevisione());
        assertEquals("PUBLISHED", data.getStato());
    }

    @Test
    @DisplayName("Test FdrCompleteData with setters")
    void testFdrCompleteDataSetters() {
        FdrCompleteData data = new FdrCompleteData(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null
        );

        LocalDateTime now = LocalDateTime.now();

        data.setFrTempId(1L);
        data.setCodPsp("PSP_01");
        data.setCodDominio("12345678901");
        data.setCodFlusso("FDR-001");
        data.setIur("IUR-123");
        data.setDataOraFlusso(now);
        data.setDataRegolamento(now);
        data.setNumeroPagamenti(100L);
        data.setImportoTotalePagamenti(5000.0);
        data.setCodBicRiversamento("BIC123");
        data.setCodPspMittente("PSP_01");
        data.setRagioneSocialePsp("PSP Name");
        data.setRagioneSocialeDominio("EC Name");
        data.setCodIntermediarioPsp("INT_01");
        data.setCodCanale("CHAN_01");
        data.setDataOraPubblicazione(now);
        data.setDataOraAggiornamento(now);
        data.setRevisione(1L);
        data.setStato("PUBLISHED");

        assertEquals(1L, data.getFrTempId());
        assertEquals("PSP_01", data.getCodPsp());
        assertEquals("12345678901", data.getCodDominio());
        assertEquals("FDR-001", data.getCodFlusso());
    }

    @Test
    @DisplayName("Test processing with all status types")
    void testProcessWithDifferentStatuses() throws Exception {
        FrTemp frTemp = createFrTemp();

        for (ReportingFlowStatusEnum status : ReportingFlowStatusEnum.values()) {
            SingleFlowResponse flowResponse = new SingleFlowResponse();
            flowResponse.setStatus(status);
            flowResponse.setRegulation("IUR-123");
            flowResponse.setTotPayments(10L);
            flowResponse.setSumPayments(100.0);

            when(fdrApiService.getSinglePublishedFlow(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(flowResponse);

            FdrCompleteData result = processor.process(frTemp);

            assertNotNull(result);
            assertEquals(status.name(), result.getStato());
        }
    }
}
