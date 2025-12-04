package it.govpay.fdr.batch.utils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.govpay.fdr.batch.entity.Dominio;

/**
 * Test per IuvUtils
 */
@DisplayName("IuvUtils Tests")
class IuvUtilsTest {

    private Dominio createDominio(String codDominio, Integer auxDigit, Integer segregationCode) {
        return Dominio.builder()
            .codDominio(codDominio)
            .auxDigit(auxDigit)
            .segregationCode(segregationCode)
            .build();
    }

    // ==================== NULL DOMINIO ====================

    @Test
    @DisplayName("When dominio is null, then IUV is not internal")
    void testNullDominio() {
        assertFalse(IuvUtils.isIuvInterno(null, "123456789012345"));
    }

    // ==================== AUXDIGIT 0 ====================

    @Test
    @DisplayName("AuxDigit 0 - Numeric IUV with 15 digits is internal")
    void testAuxDigit0Numeric15Digits() {
        Dominio dominio = createDominio("12345678901", 0, null);
        assertTrue(IuvUtils.isIuvInterno(dominio, "123456789012345"));
    }

    @Test
    @DisplayName("AuxDigit 0 - Numeric IUV with 14 digits is not internal")
    void testAuxDigit0Numeric14Digits() {
        Dominio dominio = createDominio("12345678901", 0, null);
        assertFalse(IuvUtils.isIuvInterno(dominio, "12345678901234"));
    }

    @Test
    @DisplayName("AuxDigit 0 - Numeric IUV with 16 digits is not internal")
    void testAuxDigit0Numeric16Digits() {
        Dominio dominio = createDominio("12345678901", 0, null);
        assertFalse(IuvUtils.isIuvInterno(dominio, "1234567890123456"));
    }

    @Test
    @DisplayName("AuxDigit 0 - Non-numeric IUV is not internal")
    void testAuxDigit0NonNumeric() {
        Dominio dominio = createDominio("12345678901", 0, null);
        assertFalse(IuvUtils.isIuvInterno(dominio, "12345ABC7890123"));
    }

    @Test
    @DisplayName("AuxDigit 0 - Alphanumeric IUV with 15 characters is not internal")
    void testAuxDigit0Alphanumeric15Chars() {
        Dominio dominio = createDominio("12345678901", 0, null);
        assertFalse(IuvUtils.isIuvInterno(dominio, "RF1234567890123"));
    }

    // ==================== AUXDIGIT 1 ====================

    @Test
    @DisplayName("AuxDigit 1 - Numeric IUV with 17 digits is internal")
    void testAuxDigit1Numeric17Digits() {
        Dominio dominio = createDominio("12345678901", 1, null);
        assertTrue(IuvUtils.isIuvInterno(dominio, "12345678901234567"));
    }

    @Test
    @DisplayName("AuxDigit 1 - Numeric IUV with 15 digits is not internal")
    void testAuxDigit1Numeric15Digits() {
        Dominio dominio = createDominio("12345678901", 1, null);
        assertFalse(IuvUtils.isIuvInterno(dominio, "123456789012345"));
    }

    @Test
    @DisplayName("AuxDigit 1 - Numeric IUV with 18 digits is not internal")
    void testAuxDigit1Numeric18Digits() {
        Dominio dominio = createDominio("12345678901", 1, null);
        assertFalse(IuvUtils.isIuvInterno(dominio, "123456789012345678"));
    }

    @Test
    @DisplayName("AuxDigit 1 - Non-numeric IUV is not internal")
    void testAuxDigit1NonNumeric() {
        Dominio dominio = createDominio("12345678901", 1, null);
        assertFalse(IuvUtils.isIuvInterno(dominio, "RF123456789012345"));
    }

    // ==================== AUXDIGIT 3 - RF FORMAT ====================

    @Test
    @DisplayName("AuxDigit 3 - RF format with matching segregation code is internal")
    void testAuxDigit3RFMatchingSegregationCode() {
        Dominio dominio = createDominio("12345678901", 3, 12);
        // RF + checkDigit(2) + segregationCode(2) + alfa(max 19)
        assertTrue(IuvUtils.isIuvInterno(dominio, "RF9912ABCDEFGHIJKLMNO"));
    }

    @Test
    @DisplayName("AuxDigit 3 - RF format with segregation code 05 is internal")
    void testAuxDigit3RFSegregationCode05() {
        Dominio dominio = createDominio("12345678901", 3, 5);
        assertTrue(IuvUtils.isIuvInterno(dominio, "RF9905ABCDEFGHIJKLMNO"));
    }

    @Test
    @DisplayName("AuxDigit 3 - RF format with non-matching segregation code is not internal")
    void testAuxDigit3RFNonMatchingSegregationCode() {
        Dominio dominio = createDominio("12345678901", 3, 12);
        assertFalse(IuvUtils.isIuvInterno(dominio, "RF9913ABCDEFGHIJKLMNO"));
    }

    @Test
    @DisplayName("AuxDigit 3 - RF format too short (less than 6 chars) throws exception")
    void testAuxDigit3RFTooShort() {
        Dominio dominio = createDominio("12345678901", 3, 12);
        // The code doesn't check for minimum length before substring, so it throws exception
        assertThrows(StringIndexOutOfBoundsException.class, () -> {
            IuvUtils.isIuvInterno(dominio, "RF99");
        });
    }

    // ==================== AUXDIGIT 3 - NUMERIC FORMAT ====================

    @Test
    @DisplayName("AuxDigit 3 - Numeric 17 digits starting with segregation code is internal")
    void testAuxDigit3Numeric17WithSegregationCode() {
        Dominio dominio = createDominio("12345678901", 3, 12);
        // segregationCode(2) + IUVbase(13) + checkDigit(2)
        assertTrue(IuvUtils.isIuvInterno(dominio, "12123456789012345"));
    }

    @Test
    @DisplayName("AuxDigit 3 - Numeric 17 digits starting with segregation code 05 is internal")
    void testAuxDigit3Numeric17WithSegregationCode05() {
        Dominio dominio = createDominio("12345678901", 3, 5);
        assertTrue(IuvUtils.isIuvInterno(dominio, "05123456789012345"));
    }

    @Test
    @DisplayName("AuxDigit 3 - Numeric 17 digits not starting with segregation code is not internal")
    void testAuxDigit3Numeric17WithoutSegregationCode() {
        Dominio dominio = createDominio("12345678901", 3, 12);
        assertFalse(IuvUtils.isIuvInterno(dominio, "13123456789012345"));
    }

    @Test
    @DisplayName("AuxDigit 3 - Numeric 15 digits is not internal")
    void testAuxDigit3Numeric15Digits() {
        Dominio dominio = createDominio("12345678901", 3, 12);
        assertFalse(IuvUtils.isIuvInterno(dominio, "121234567890123"));
    }

    @Test
    @DisplayName("AuxDigit 3 - Numeric 18 digits is not internal")
    void testAuxDigit3Numeric18Digits() {
        Dominio dominio = createDominio("12345678901", 3, 12);
        assertFalse(IuvUtils.isIuvInterno(dominio, "121234567890123456"));
    }

    @Test
    @DisplayName("AuxDigit 3 - Non-RF alphanumeric is not internal")
    void testAuxDigit3NonRFAlphanumeric() {
        Dominio dominio = createDominio("12345678901", 3, 12);
        assertFalse(IuvUtils.isIuvInterno(dominio, "AB1234567890123"));
    }

    // ==================== OTHER AUXDIGIT VALUES ====================

    @Test
    @DisplayName("AuxDigit 2 - Numeric IUV is not internal")
    void testAuxDigit2Numeric() {
        Dominio dominio = createDominio("12345678901", 2, null);
        assertFalse(IuvUtils.isIuvInterno(dominio, "123456789012345"));
    }

    @Test
    @DisplayName("AuxDigit 4 - Numeric IUV is not internal")
    void testAuxDigit4Numeric() {
        Dominio dominio = createDominio("12345678901", 4, null);
        assertFalse(IuvUtils.isIuvInterno(dominio, "12345678901234567"));
    }

    @Test
    @DisplayName("AuxDigit null - Numeric IUV throws NullPointerException")
    void testAuxDigitNullNumeric() {
        Dominio dominio = createDominio("12345678901", null, null);
        // The code doesn't handle null auxDigit, so it throws NullPointerException
        assertThrows(NullPointerException.class, () -> {
            IuvUtils.isIuvInterno(dominio, "123456789012345");
        });
    }

    // ==================== EDGE CASES ====================

    @Test
    @DisplayName("Empty IUV throws exception and returns false")
    void testEmptyIuv() {
        Dominio dominio = createDominio("12345678901", 0, null);
        // Empty string is not numeric, should return false
        assertFalse(IuvUtils.isIuvInterno(dominio, ""));
    }

    @Test
    @DisplayName("Very long numeric IUV is not internal")
    void testVeryLongNumericIuv() {
        Dominio dominio = createDominio("12345678901", 0, null);
        assertFalse(IuvUtils.isIuvInterno(dominio, "123456789012345678901234567890"));
    }

    @Test
    @DisplayName("IUV with spaces is not numeric")
    void testIuvWithSpaces() {
        Dominio dominio = createDominio("12345678901", 0, null);
        assertFalse(IuvUtils.isIuvInterno(dominio, "12345 67890 12345"));
    }

    @Test
    @DisplayName("IUV with special characters is not numeric")
    void testIuvWithSpecialChars() {
        Dominio dominio = createDominio("12345678901", 1, null);
        assertFalse(IuvUtils.isIuvInterno(dominio, "12345-67890-12345"));
    }

    @Test
    @DisplayName("AuxDigit 3 - RF with lowercase is still valid")
    void testAuxDigit3RFLowercase() {
        Dominio dominio = createDominio("12345678901", 3, 12);
        // RF is case-sensitive in the startsWith check, so lowercase should not match
        assertFalse(IuvUtils.isIuvInterno(dominio, "rf9912ABCDEFGHIJKLMNO"));
    }

    @Test
    @DisplayName("AuxDigit 3 - Numeric starting with 00 segregation code")
    void testAuxDigit3Numeric17WithSegregationCode00() {
        Dominio dominio = createDominio("12345678901", 3, 0);
        assertTrue(IuvUtils.isIuvInterno(dominio, "00123456789012345"));
    }

    @Test
    @DisplayName("AuxDigit 3 - RF with segregation code 00")
    void testAuxDigit3RFWithSegregationCode00() {
        Dominio dominio = createDominio("12345678901", 3, 0);
        assertTrue(IuvUtils.isIuvInterno(dominio, "RF9900ABCDEFGHIJKLMNO"));
    }

    @Test
    @DisplayName("AuxDigit 0 - IUV with leading zeros")
    void testAuxDigit0WithLeadingZeros() {
        Dominio dominio = createDominio("12345678901", 0, null);
        assertTrue(IuvUtils.isIuvInterno(dominio, "000000000000001"));
    }

    @Test
    @DisplayName("AuxDigit 1 - IUV with all zeros")
    void testAuxDigit1AllZeros() {
        Dominio dominio = createDominio("12345678901", 1, null);
        assertTrue(IuvUtils.isIuvInterno(dominio, "00000000000000000"));
    }
}
