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

import androidx.annotation.Nullable;

/**
 * Reads the type field of a FIT file's file_id message without the rest of Gadgetbridge's FIT
 * parser, so files already on the phone can be checked cheaply (and in a plain JVM test).
 * Type 4 is an activity; monitoring, sleep and metrics files have other types.
 */
public final class FitActivityCheck {
    public static final int FIT_TYPE_ACTIVITY = 4;

    private FitActivityCheck() {
    }

    public static boolean isActivity(@Nullable final byte[] data) {
        final Integer type = fileIdType(data);
        return type != null && type == FIT_TYPE_ACTIVITY;
    }

    /** The file_id type of a FIT file, or null when the bytes are not a FIT file or have no file_id. */
    @Nullable
    public static Integer fileIdType(@Nullable final byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        final int headerSize = data[0] & 0xFF;
        if (headerSize < 12 || headerSize > data.length) {
            return null;
        }
        if (data[8] != '.' || data[9] != 'F' || data[10] != 'I' || data[11] != 'T') {
            return null;
        }
        final long dataSize = (data[4] & 0xFFL) | (data[5] & 0xFFL) << 8 | (data[6] & 0xFFL) << 16 | (data[7] & 0xFFL) << 24;
        final long end = Math.min(data.length, headerSize + dataSize);

        // Definitions by local message type: the global message number and the field sizes.
        final int[] globalNum = new int[16];
        final boolean[] defined = new boolean[16];
        final int[][] fieldNums = new int[16][];
        final int[][] fieldSizes = new int[16][];
        final int[] devBytes = new int[16];

        int pos = headerSize;
        // file_id comes first in every real file; give up after a few records rather than walk the whole file.
        int records = 0;
        while (pos < end && records++ < 64) {
            final int h = data[pos++] & 0xFF;
            if ((h & 0x80) != 0) {
                // Compressed timestamp header: a data message of local type 0-3.
                final int local = (h >> 5) & 0x03;
                if (!defined[local]) return null;
                pos += dataLength(fieldSizes[local], devBytes[local]);
                continue;
            }
            final int local = h & 0x0F;
            if ((h & 0x40) != 0) {
                // Definition message.
                if (pos + 5 > end) return null;
                final boolean big = data[pos + 1] == 1;
                final int global = big
                        ? (data[pos + 2] & 0xFF) << 8 | (data[pos + 3] & 0xFF)
                        : (data[pos + 2] & 0xFF) | (data[pos + 3] & 0xFF) << 8;
                final int n = data[pos + 4] & 0xFF;
                pos += 5;
                if (pos + 3L * n > end) return null;
                final int[] nums = new int[n];
                final int[] sizes = new int[n];
                for (int i = 0; i < n; i++) {
                    nums[i] = data[pos] & 0xFF;
                    sizes[i] = data[pos + 1] & 0xFF;
                    pos += 3;
                }
                int dev = 0;
                if ((h & 0x20) != 0) {
                    if (pos >= end) return null;
                    final int nd = data[pos++] & 0xFF;
                    if (pos + 3L * nd > end) return null;
                    for (int i = 0; i < nd; i++) {
                        dev += data[pos + 1] & 0xFF;
                        pos += 3;
                    }
                }
                defined[local] = true;
                globalNum[local] = global;
                fieldNums[local] = nums;
                fieldSizes[local] = sizes;
                devBytes[local] = dev;
            } else {
                // Data message.
                if (!defined[local]) return null;
                if (globalNum[local] == 0) {
                    // file_id: field 0 is the type (an enum, one byte).
                    int p = pos;
                    for (int i = 0; i < fieldNums[local].length; i++) {
                        if (fieldNums[local][i] == 0 && fieldSizes[local][i] >= 1) {
                            if (p >= end) return null;
                            final int value = data[p] & 0xFF;
                            return value == 0xFF ? null : value;
                        }
                        p += fieldSizes[local][i];
                    }
                    return null;
                }
                pos += dataLength(fieldSizes[local], devBytes[local]);
            }
        }
        return null;
    }

    private static int dataLength(final int[] sizes, final int devBytes) {
        int total = devBytes;
        for (final int s : sizes) {
            total += s;
        }
        return total;
    }
}
