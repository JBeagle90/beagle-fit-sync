/*  Copyright (C) 2026 C.F.R. Inc.

    This file is part of Gadgetbridge (Beagle Fit Sync).

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.beaglefit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Writes weigh-ins as a FIT weight file, the kind a Garmin Index scale exports: a file_id of type 9
 * and one weight_scale message (30) per weigh-in. beagle.fit already reads these (js/garmin.js,
 * weighIns), so weigh-ins from Health Connect take the same upload and inbox as runs. Plain JVM.
 */
public final class FitWeightFile {
    public static final int FIT_TYPE_WEIGHT = 9;
    /** Seconds from the Unix epoch to FIT's, 1989-12-31T00:00:00Z. */
    static final long FIT_EPOCH = 631065600L;
    private static final int MANUFACTURER_DEVELOPMENT = 255;
    private static final int PROFILE_VERSION = 2132;

    private FitWeightFile() {
    }

    /** One weigh-in: when (Unix milliseconds), the weight in kg, and body fat in percent if the scale read it. */
    public static final class Reading {
        public final long timeMs;
        public final double kg;
        @Nullable public final Double fatPct;

        public Reading(final long timeMs, final double kg, @Nullable final Double fatPct) {
            this.timeMs = timeMs;
            this.kg = kg;
            this.fatPct = fatPct;
        }
    }

    @NonNull
    public static byte[] build(@NonNull final List<Reading> readings, final long createdMs) {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        // file_id (local 0): type, manufacturer, time_created.
        body.write(0x40);
        body.write(new byte[]{0, 0, 0, 0, 3, 0, 1, 0x00, 1, 2, (byte) 0x84, 4, 4, (byte) 0x86}, 0, 14);
        body.write(0x00);
        body.write(FIT_TYPE_WEIGHT);
        u16(body, MANUFACTURER_DEVELOPMENT);
        u32(body, fitTime(createdMs));

        // weight_scale (local 1): timestamp, weight (kg × 100), percent_fat (× 100).
        body.write(0x41);
        body.write(new byte[]{0, 0, 30, 0, 3, (byte) 253, 4, (byte) 0x86, 0, 2, (byte) 0x84, 1, 2, (byte) 0x84}, 0, 14);
        for (final Reading r : readings) {
            body.write(0x01);
            u32(body, fitTime(r.timeMs));
            u16(body, scaled(r.kg));
            u16(body, r.fatPct == null ? 0xFFFF : scaled(r.fatPct));
        }

        final byte[] data = body.toByteArray();
        final ByteArrayOutputStream out = new ByteArrayOutputStream(14 + data.length + 2);
        final byte[] header = new byte[14];
        header[0] = 14;
        header[1] = 0x20;
        header[2] = (byte) (PROFILE_VERSION & 0xFF);
        header[3] = (byte) (PROFILE_VERSION >> 8);
        header[4] = (byte) data.length;
        header[5] = (byte) (data.length >> 8);
        header[6] = (byte) (data.length >> 16);
        header[7] = (byte) (data.length >> 24);
        header[8] = '.';
        header[9] = 'F';
        header[10] = 'I';
        header[11] = 'T';
        final int headerCrc = crc(0, header, 0, 12);
        header[12] = (byte) headerCrc;
        header[13] = (byte) (headerCrc >> 8);
        out.write(header, 0, 14);
        out.write(data, 0, data.length);
        // The file's CRC covers the header too.
        final int fileCrc = crc(crc(0, header, 0, 14), data, 0, data.length);
        out.write(fileCrc & 0xFF);
        out.write((fileCrc >> 8) & 0xFF);
        return out.toByteArray();
    }

    static long fitTime(final long unixMs) {
        return unixMs / 1000L - FIT_EPOCH;
    }

    /** Hundredths, kept under FIT's invalid values (0xFFFF, and 0xFFFE that the site skips). */
    private static int scaled(final double value) {
        return (int) Math.max(0, Math.min(0xFFFD, Math.round(value * 100)));
    }

    private static void u16(final ByteArrayOutputStream out, final int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void u32(final ByteArrayOutputStream out, final long v) {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 24) & 0xFF));
    }

    private static final int[] CRC_TABLE = {
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    };

    /** FIT's CRC-16, from the FIT SDK. */
    static int crc(int crc, final byte[] data, final int from, final int to) {
        for (int i = from; i < to; i++) {
            final int b = data[i] & 0xFF;
            int tmp = CRC_TABLE[crc & 0xF];
            crc = (crc >> 4) & 0x0FFF;
            crc = crc ^ tmp ^ CRC_TABLE[b & 0xF];
            tmp = CRC_TABLE[crc & 0xF];
            crc = (crc >> 4) & 0x0FFF;
            crc = crc ^ tmp ^ CRC_TABLE[(b >> 4) & 0xF];
        }
        return crc;
    }
}
