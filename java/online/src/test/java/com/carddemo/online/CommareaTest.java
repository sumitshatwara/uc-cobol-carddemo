package com.carddemo.online;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommareaTest extends OnlineTestSupport {
    @Test
    @DisplayName("BR-ONL-043 COMMAREA layout and pseudo-conversational return")
    void brOnl043_commareaLayout() {
        // Given
        var session = update.search("00000000001", null).session();

        // When
        String encoded = session.encodeCommarea();

        // Then
        assertEquals(2000, encoded.length());
        assertEquals(session.commarea().encode(), encoded.substring(0, 160));
        assertEquals("00000000001", encoded.substring(118, 129));
        assertEquals('S', encoded.charAt(160));
        assertEquals("00000000001", encoded.substring(161, 172));
        assertEquals("5000.00     ", encoded.substring(185, 197));
        assertEquals("00000000001", encoded.substring(597, 608));
        assertEquals("5000.00     ", encoded.substring(621, 633));
    }
}
