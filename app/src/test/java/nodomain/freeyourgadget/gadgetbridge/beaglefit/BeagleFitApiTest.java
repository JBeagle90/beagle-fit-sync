package nodomain.freeyourgadget.gadgetbridge.beaglefit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** The upload and link responses from beagle.fit, and recognising activity FIT files. Plain JVM. */
public class BeagleFitApiTest {
    private static BeagleFitApi.Outcome outcome(final int status, final String body) {
        return BeagleFitApi.classifyUpload(status, body).outcome;
    }

    @Test
    public void uploadSent() {
        final BeagleFitApi.UploadResult r = BeagleFitApi.classifyUpload(200, "{\"ok\":true,\"id\":\"abc\",\"dup\":false}");
        assertEquals(BeagleFitApi.Outcome.SENT, r.outcome);
        assertFalse(r.dup);
    }

    @Test
    public void uploadDuplicateIsSuccess() {
        final BeagleFitApi.UploadResult r = BeagleFitApi.classifyUpload(200, "{\"ok\":true,\"id\":\"abc\",\"dup\":true}");
        assertEquals(BeagleFitApi.Outcome.SENT, r.outcome);
        assertTrue(r.dup);
    }

    @Test
    public void uploadOkWithoutJsonIsRetried() {
        // A captive portal or proxy page answering 200 must not count as sent.
        assertEquals(BeagleFitApi.Outcome.RETRY, outcome(200, "<html>Sign in to Wi-Fi</html>"));
        assertEquals(BeagleFitApi.Outcome.RETRY, outcome(200, null));
    }

    @Test
    public void uploadUnlinked() {
        assertEquals(BeagleFitApi.Outcome.UNLINKED,
                outcome(401, "{\"error\":{\"code\":\"phone\",\"message\":\"This phone is not linked.\"}}"));
        assertEquals(BeagleFitApi.Outcome.UNLINKED, outcome(401, ""));
    }

    @Test
    public void uploadSkipped() {
        assertEquals(BeagleFitApi.Outcome.SKIP, outcome(413, "{\"error\":{\"code\":\"size\",\"message\":\"Too big\"}}"));
        assertEquals(BeagleFitApi.Outcome.SKIP, outcome(413, null));
        assertEquals(BeagleFitApi.Outcome.SKIP, outcome(400, "{\"error\":{\"code\":\"fit\",\"message\":\"Not a FIT file\"}}"));
    }

    @Test
    public void uploadRetried() {
        assertEquals(BeagleFitApi.Outcome.RETRY, outcome(507, "{\"error\":{\"code\":\"inbox_full\",\"message\":\"Inbox full\"}}"));
        assertEquals(BeagleFitApi.Outcome.RETRY, outcome(500, "oops"));
        assertEquals(BeagleFitApi.Outcome.RETRY, outcome(502, null));
        assertEquals(BeagleFitApi.Outcome.RETRY, outcome(503, ""));
        assertEquals(BeagleFitApi.Outcome.RETRY, outcome(429, "{\"error\":{\"code\":\"rate\",\"message\":\"Slow down\"}}"));
    }

    @Test
    public void otherClientErrorsKeepTheFile() {
        assertEquals(BeagleFitApi.Outcome.KEEP, outcome(400, "{\"error\":{\"code\":\"name\",\"message\":\"Bad name\"}}"));
        assertEquals(BeagleFitApi.Outcome.KEEP, outcome(403, null));
    }

    @Test
    public void linkSuccess() {
        final BeagleFitApi.LinkResult r = BeagleFitApi.parseLink(200,
                "{\"key\":\"k123\",\"phone\":{\"id\":\"p1\",\"name\":\"Pixel 8\"},\"email\":\"a@b.c\"}");
        assertTrue(r.ok);
        assertEquals("k123", r.key);
        assertEquals("p1", r.phoneId);
        assertEquals("Pixel 8", r.phoneName);
        assertEquals("a@b.c", r.email);
        assertNull(r.error);
    }

    @Test
    public void linkErrorsShowTheServerMessage() {
        final BeagleFitApi.LinkResult wrong = BeagleFitApi.parseLink(400,
                "{\"error\":{\"code\":\"code\",\"message\":\"That code is wrong or has expired.\"}}");
        assertFalse(wrong.ok);
        assertEquals("That code is wrong or has expired.", wrong.error);

        final BeagleFitApi.LinkResult tooMany = BeagleFitApi.parseLink(429,
                "{\"error\":{\"code\":\"rate\",\"message\":\"Too many tries.\"}}");
        assertFalse(tooMany.ok);
        assertEquals("Too many tries.", tooMany.error);

        assertFalse(BeagleFitApi.parseLink(429, null).ok);
        assertFalse(BeagleFitApi.parseLink(200, "{}").ok);
    }

    @Test
    public void normalizesCodeAndUrl() {
        assertEquals("ABCD-EFGH", BeagleFitApi.normalizeCode("abcd efgh"));
        assertEquals("ABCD-EFGH", BeagleFitApi.normalizeCode(" ABCD-EFGH "));
        assertEquals("https://beagle.fit", BeagleFitApi.normalizeBaseUrl("beagle.fit/"));
        assertEquals("http://192.168.1.20:5181", BeagleFitApi.normalizeBaseUrl("http://192.168.1.20:5181/"));
        assertEquals(BeagleFitApi.DEFAULT_BASE_URL, BeagleFitApi.normalizeBaseUrl("  "));
    }

    @Test
    public void recognisesActivityFiles() throws IOException {
        assertTrue(FitActivityCheck.isActivity(resource("/TestFitFileDevelopersField.fit")));
        assertEquals(Integer.valueOf(2), FitActivityCheck.fileIdType(resource("/TestFitFileSettings2.fit")));
        assertFalse(FitActivityCheck.isActivity(resource("/TestFitFileSettings2.fit")));
        assertFalse(FitActivityCheck.isActivity(resource("/TestFitLocationEncoding.fit")));
        assertFalse(FitActivityCheck.isActivity(resource("/gpx-exporter-test-SampleTrack.course.fit")));
        assertFalse(FitActivityCheck.isActivity("not a fit file at all".getBytes()));
        assertFalse(FitActivityCheck.isActivity(new byte[0]));
        assertFalse(FitActivityCheck.isActivity(null));
    }

    @Test
    public void recognisesASmallHandMadeActivityFile() {
        // 12-byte header, a definition of file_id (local 0) with type (field 0, 1 byte), then its data.
        final byte[] body = {
                0x40, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, // definition: global 0, one field
                0x00, 0x04                                           // data: type = 4 (activity)
        };
        final byte[] fit = new byte[12 + body.length + 2];
        fit[0] = 12;
        fit[1] = 0x10;
        fit[4] = (byte) body.length;
        fit[8] = '.';
        fit[9] = 'F';
        fit[10] = 'I';
        fit[11] = 'T';
        System.arraycopy(body, 0, fit, 12, body.length);
        assertTrue(FitActivityCheck.isActivity(fit));
        fit[12 + body.length - 1] = 0x20; // type = 32 (monitoring)
        assertFalse(FitActivityCheck.isActivity(fit));
    }

    private byte[] resource(final String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing test resource " + name);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
