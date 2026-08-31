package org.soundnet.flasher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BoardRevisionTest {

    /** Ids must track firmware/libraries/xsens/xsensmessage.h. */
    @Test
    void idsMatchTheFirmwareHeader() {
        assertEquals(5, BoardRevision.SOUNDNET_V1_R5.id());
        assertEquals(6, BoardRevision.SOUNDNET_V1_R6.id());
        assertEquals(10, BoardRevision.SOUNDNET_V2_R1.id());
        assertEquals(200, BoardRevision.SENSLOGGER_V1.id());
    }

    @Test
    void resolvesTheIdReportedByASensor() {
        assertEquals(BoardRevision.SOUNDNET_V1_R6, BoardRevision.fromId(6));
        assertNull(BoardRevision.fromId(7));
    }

    @Test
    void resolvesCatalogNames() {
        assertEquals(BoardRevision.SOUNDNET_V1_R6, BoardRevision.fromName("SOUNDNET_V1_R6"));
        assertNull(BoardRevision.fromName("NOT_A_REVISION"));
    }
}
