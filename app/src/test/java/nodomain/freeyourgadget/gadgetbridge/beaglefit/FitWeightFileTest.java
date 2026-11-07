package nodomain.freeyourgadget.gadgetbridge.beaglefit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Weigh-ins written as a scale's FIT file: the header, the file type and the CRCs. Plain JVM. */
public class FitWeightFileTest {
    private static final long WHEN = 1793001600000L; // 2026-10-26T08:00:00Z

    @Test
    public void typeIsWeight() {
        final byte[] f = FitWeightFile.build(Collections.singletonList(new FitWeightFile.Reading(WHEN, 72.35, 18.4)), WHEN);
        assertEquals(Integer.valueOf(FitWeightFile.FIT_TYPE_WEIGHT), FitActivityCheck.fileIdType(f));
        assertTrue(!FitActivityCheck.isActivity(f));
    }

    @Test
    public void sizesAndCrcs() {
        final byte[] f = FitWeightFile.build(Arrays.asList(
                new FitWeightFile.Reading(WHEN, 72.35, 18.4),
                new FitWeightFile.Reading(WHEN + 86400000L, 72.1, null)), WHEN);
        // header 14, file_id definition 15 + data 8, weight_scale definition 15 + 2 × 9, CRC 2
        assertEquals(14 + 15 + 8 + 15 + 18 + 2, f.length);
        final int dataSize = (f[4] & 0xFF) | (f[5] & 0xFF) << 8 | (f[6] & 0xFF) << 16 | (f[7] & 0xFF) << 24;
        assertEquals(f.length - 16, dataSize);
        // A header and a file with their CRCs on the end check to 0.
        assertEquals(0, FitWeightFile.crc(0, f, 0, 14));
        assertEquals(0, FitWeightFile.crc(0, f, 0, f.length));
    }

    @Test
    public void readingFields() {
        final byte[] f = FitWeightFile.build(Collections.singletonList(new FitWeightFile.Reading(WHEN, 72.35, null)), WHEN);
        final int at = 14 + 15 + 8 + 15 + 1; // the first weight_scale message, after its header byte
        final long ts = (f[at] & 0xFFL) | (f[at + 1] & 0xFFL) << 8 | (f[at + 2] & 0xFFL) << 16 | (f[at + 3] & 0xFFL) << 24;
        assertEquals(WHEN / 1000 - FitWeightFile.FIT_EPOCH, ts);
        assertEquals(7235, (f[at + 4] & 0xFF) | (f[at + 5] & 0xFF) << 8);
        assertEquals(0xFFFF, (f[at + 6] & 0xFF) | (f[at + 7] & 0xFF) << 8); // no body fat
    }
}
