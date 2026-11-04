package com.carddemo.online;

import com.carddemo.online.routines.DateFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateFormatterTest {
    private final DateFormatter formatter = new DateFormatter();

    @Test
    @DisplayName("BR-ONL-044 COBDATFT linkage, registers and return code")
    void brOnl044_returnCode() {
        // Given
        var record = record("2", "2022-07-05", "2");

        // When
        int returnCode = formatter.convert(record);

        // Then
        assertEquals(0, returnCode);
        assertEquals("20220705", record.outDate.substring(0, 8));
        assertEquals(" ".repeat(38), record.errMsg);
    }

    @Test
    @DisplayName("BR-ONL-045 COBDATFT type 1: YYYYMMDD to YYYY-MM-DD")
    void brOnl045_typeOneConversion() {
        // Given
        var record = record("1", "20220705", "2");
        record.errMsg = "UNCHANGED".concat(" ".repeat(29));
        String originalError = record.errMsg;

        // When
        int returnCode = formatter.convert(record);

        // Then
        assertEquals("2022-07-05", record.outDate.substring(0, 10));
        assertEquals(originalError, record.errMsg);
        assertEquals(0, returnCode);
    }

    @Test
    @DisplayName("BR-ONL-045 COBDATFT type 1: YYYYMMDD to YYYY-MM-DD — hyphen finding")
    void brOnl045_typeOneHyphenFinding() {
        // Given
        var record = record("1", "2022-07-05", "2");

        // When
        formatter.convert(record);

        // Then
        assertEquals("2022--0-7-", record.outDate.substring(0, 10));
        assertEquals(" ".repeat(38), record.errMsg);
    }

    @Test
    @DisplayName("BR-ONL-046 COBDATFT type 2: YYYY-MM-DD to YYYYMMDD (separator not checked)")
    void brOnl046_typeTwoSeparatorFinding() {
        // Given
        var record = record("2", "2022/07/05", "2");
        record.outDate = "ABCDEFGHIJABCDEFGHIJ";
        String originalTail = record.outDate.substring(8);

        // When
        int returnCode = formatter.convert(record);

        // Then
        assertEquals("20220705", record.outDate.substring(0, 8));
        assertEquals(originalTail, record.outDate.substring(8));
        assertEquals(0, returnCode);
    }

    @Test
    @DisplayName("BR-ONL-047 COBDATFT INVALID INPUT")
    void brOnl047_invalidInputAndStaleError() {
        // Given
        var invalidType = record("3", "20220705", "3");
        invalidType.outDate = "UNCHANGED".concat(" ".repeat(11));
        String originalOutput = invalidType.outDate;
        var invalidTypeOneOutput = record("1", "20220705", "1");
        var invalidTypeTwoOutput = record("2", "2022-07-05", "1");
        var successfulWithOldError = record("2", "2022-07-05", "2");
        successfulWithOldError.errMsg = "OLD ERROR".concat(" ".repeat(29));

        // When
        formatter.convert(invalidType);
        formatter.convert(invalidTypeOneOutput);
        formatter.convert(invalidTypeTwoOutput);
        formatter.convert(successfulWithOldError);

        // Then
        assertTrue(invalidType.errMsg.startsWith("INVALID INPUT"));
        assertEquals(" ".repeat(25), invalidType.errMsg.substring(13));
        assertEquals(originalOutput, invalidType.outDate);
        assertTrue(invalidTypeOneOutput.errMsg.startsWith("INVALID INPUT"));
        assertTrue(invalidTypeTwoOutput.errMsg.startsWith("INVALID INPUT"));
        assertEquals("20220705", successfulWithOldError.outDate.substring(0, 8));
        assertEquals("OLD ERROR".concat(" ".repeat(29)), successfulWithOldError.errMsg);
    }

    private static DateFormatter.CodatecnRecord record(String inputType, String inputDate,
                                                        String outputType) {
        DateFormatter.CodatecnRecord record = new DateFormatter.CodatecnRecord();
        record.inType = inputType;
        record.inDate = inputDate;
        record.outType = outputType;
        return record;
    }
}
