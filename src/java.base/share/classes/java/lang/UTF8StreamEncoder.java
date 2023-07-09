/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package java.lang;

import jdk.internal.misc.InternalLock;
import jdk.internal.misc.Unsafe;
import sun.nio.cs.StreamEncoder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class UTF8StreamEncoder extends StreamEncoder {

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final boolean BIG_ENDIAN = UNSAFE.isBigEndian();

    // -- Public methods corresponding to those in OutputStreamWriter --

    // All synchronization and state/argument checking is done in these public
    // methods; the concrete stream-encoder subclasses defined below need not
    // do any such checking.

    public void write(int c) throws IOException {
        Object lock = this.lock;
        if (lock instanceof InternalLock locker) {
            locker.lock();
            try {
                lockedWrite(c);
            } finally {
                locker.unlock();
            }
        } else {
            synchronized (lock) {
                lockedWrite(c);
            }
        }
    }

    private void lockedWrite(int c) throws IOException {
        ensureOpen();
        putChar((char) c);
    }

    private void lockedWrite(String str, int off, int len) throws IOException {
        ensureOpen();
        Objects.checkFromIndexSize(off, len, str.length());

        if (len == 0) {
            return;
        }

        byte coder = str.coder();
        byte[] bytes = str.value();

        if (coder == String.LATIN1) {
            implWriteLatin1(bytes, off, len);
        } else {
            implWriteUTF16(bytes, Unsafe.ARRAY_BYTE_BASE_OFFSET + ((long) off << 1), len);
        }
    }

    public void write(String str, int off, int len) throws IOException {
        Object lock = this.lock;
        if (lock instanceof InternalLock locker) {
            locker.lock();
            try {
                lockedWrite(str, off, len);
            } finally {
                locker.unlock();
            }
        } else {
            synchronized (lock) {
                lockedWrite(str, off, len);
            }
        }
    }

    // -- Charset-based stream encoder impl --

    private final CodingErrorAction malformedInputAction;
    private final byte[] replacement;

    private byte[] ba;
    private int bp;

    private ByteBuffer bb;
    private final int maxBufferCapacity;

    UTF8StreamEncoder(OutputStream out, Object lock, CodingErrorAction malformedInputAction, byte[] replacement) {
        super(out, lock, StandardCharsets.UTF_8);
        this.malformedInputAction = malformedInputAction;
        this.replacement = replacement;

        this.maxBufferCapacity = MAX_BYTE_BUFFER_CAPACITY;
        this.ba = new byte[INITIAL_BYTE_BUFFER_CAPACITY];
    }

    UTF8StreamEncoder(WritableByteChannel ch, CodingErrorAction malformedInputAction, byte[] replacement, int mbc) {
        super(ch, StandardCharsets.UTF_8);
        this.malformedInputAction = malformedInputAction;
        this.replacement = replacement;

        if (mbc > 0) {
            this.maxBufferCapacity = Math.max(mbc, 4);
            this.ba = new byte[maxBufferCapacity];
        } else {
            this.maxBufferCapacity = MAX_BYTE_BUFFER_CAPACITY;
            this.ba = new byte[INITIAL_BYTE_BUFFER_CAPACITY];
        }
    }

    private static int putTwoBytesChar(byte[] ba, int off, char c) {
        int b0 = 0xc0 | (c >> 6);
        int b1 = 0x80 | (c & 0x3f);

        UNSAFE.putShortUnaligned(ba, (long) Unsafe.ARRAY_BYTE_BASE_OFFSET + off,
                (short) (BIG_ENDIAN
                        ? (b0 << 8) | b1
                        : b0 | (b1 << 8)));
        return off + 2;
    }

    private static int putThreeBytesChar(byte[] ba, int off, char c) {
        ba[off + 0] = (byte) (0xe0 | c >> 12);
        ba[off + 1] = (byte) (0x80 | c >> 6 & 0x3f);
        ba[off + 2] = (byte) (0x80 | c & 0x3f);
        return off + 3;
    }

    private static int putFourBytesChar(byte[] ba, int off, int uc) {
        int b0 = 0xf0 | (uc >> 18);
        int b1 = 0x80 | ((uc >> 12) & 0x3f);
        int b2 = 0x80 | ((uc >> 6) & 0x3f);
        int b3 = 0x80 | uc & 0x3f;

        UNSAFE.putIntUnaligned(ba, (long) Unsafe.ARRAY_BYTE_BASE_OFFSET + off, BIG_ENDIAN
                ? (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
                : b0 | (b1 << 8) | (b2 << 16) | (b3 << 24));

        return off + 4;
    }

    private void putChar(char c) throws IOException {
        if (ba.length - bp < 4) {
            implFlushBuffer();
        }

        if (haveLeftoverChar) {
            haveLeftoverChar = false;

            if (Character.isLowSurrogate(c)) {
                int uc = Character.toCodePoint(leftoverChar, c);
                if (uc >= 0) {
                    bp = putFourBytesChar(ba, bp, uc);
                    return;
                }
            }

            handleMalformed();
            putChar(c);
        } else if (c < 0x80) {
            ba[bp++] = (byte) c;
        } else if (c < 0x800) {
            bp = putTwoBytesChar(ba, bp, c);
        } else if (Character.isSurrogate(c)) {
            if (Character.isHighSurrogate(c)) {
                haveLeftoverChar = true;
                leftoverChar = c;
            } else {
                handleMalformed();
            }
        } else {
            bp = putThreeBytesChar(ba, bp, c);
        }
    }

    private void handleMalformed() throws IOException {
        if (malformedInputAction == CodingErrorAction.REPLACE) {
            for (int i = 0; i < replacement.length; ) {
                if (bp == ba.length) {
                    implFlushBuffer();
                }

                int n = Math.min(replacement.length - i, ba.length - bp);
                System.arraycopy(replacement, i, ba, bp, n);
                bp += n;
                i += n;
            }
        } else if (malformedInputAction == CodingErrorAction.REPORT) {
            throw new MalformedInputException(1);
        } else if (malformedInputAction == CodingErrorAction.IGNORE) {
            // Do nothing
        } else {
            assert false : "Unexpected malformed input action: " + malformedInputAction;
        }
    }

    @Override
    protected void implWrite(char[] cbuf, int off, int len) throws IOException {
        implWriteUTF16(cbuf, Unsafe.ARRAY_CHAR_BASE_OFFSET + ((long) off << 1), len);
    }

    private void implWriteLatin1(byte[] arr, int off, int len) throws IOException {
        growByteBufferIfNeeded(len);

        if (haveLeftoverChar) {
            haveLeftoverChar = false;
            handleMalformed();
        }

        int cap = ba.length;
        int end = off + len;
        int limit = cap - 2;

        // Cache bp into a local variable;
        // Before and after calling implFlushBuffer and handleMalformed,
        // its value needs to be resynchronized with bp.
        int count = bp;

        while (off < end) {
            // ascii loop
            int pos = StringCoding.countPositives(arr, off, end - off);
            while (pos > 0) {
                if (count == cap) {
                    bp = count;
                    implFlushBuffer();
                    count = 0;
                }

                int n = Math.min(cap - count, pos);
                System.arraycopy(arr, off, ba, count, n);

                count += n;
                off += n;
                pos -= n;
            }

            // latin1 loop
            while (off < end) {
                byte c = arr[off];
                if (c < 0) {
                    if (count > limit) {
                        bp = count;
                        implFlushBuffer();
                        count = 0;
                    }

                    count = putTwoBytesChar(ba, count, (char) (c & 0xff));
                    off++;
                } else {
                    break; // break latin1 loop
                }
            }
        }

        bp = count;
    }

    private void implWriteUTF16(Object arr, long offset, int len) throws IOException {
        growByteBufferIfNeeded(len);

        byte[] ba = this.ba;
        int cap = ba.length;
        long end = offset + ((long) len << 1);

        if (haveLeftoverChar) {
            haveLeftoverChar = false;

            char c = UNSAFE.getCharUnaligned(arr, offset);
            if (Character.isLowSurrogate(c)) {
                offset += 2;

                if (cap - bp < 4) {
                    implFlushBuffer();
                }

                int uc = Character.toCodePoint(leftoverChar, c);
                if (uc >= 0) {
                    bp = putFourBytesChar(ba, bp, uc);
                } else {
                    handleMalformed();
                }
            } else {
                handleMalformed();
            }
        }

        // Cache bp into a local variable;
        // Before and after calling implFlushBuffer and handleMalformed,
        // its value needs to be resynchronized with bp.
        int count = bp;

        // Handle ASCII-only prefix
        ascii:
        while (offset < end) {
            int rem = cap - count;
            if (rem == 0) {
                bp = count;
                flushBuffer();
                count = 0;
                rem = cap;
            }

            int limit = Math.min(rem, (int) (end - offset) >>> 1);
            for (int i = 0; i < limit; i++) {
                char c = UNSAFE.getCharUnaligned(arr, offset);
                if (c >= 0x80) {
                    break ascii;
                }

                ba[count++] = (byte) c;
                offset += 2;
            }
        }

        // To make encoding characters simpler, we keep ba has more than four bytes remaining,
        // so that we can always put one character into it at a time.
        for (int limit = cap - 4; offset < end; offset += 2) {
            if (count > limit) {
                bp = count;
                implFlushBuffer();
                count = 0;
            }

            char c = UNSAFE.getCharUnaligned(arr, offset);
            if (c < 0x80) {
                ba[count++] = (byte) c;
            } else if (c < 0x800) {
                count = putTwoBytesChar(ba, count, c);
            } else if (Character.isSurrogate(c)) {
                if (Character.isHighSurrogate(c)) {
                    if (offset < end - 2) {
                        // lookahead
                        char low = UNSAFE.getCharUnaligned(arr, offset + 2);
                        if (Character.isLowSurrogate(low)) {
                            offset += 2;

                            int uc = Character.toCodePoint(c, low);
                            if (uc >= 0) {
                                count = putFourBytesChar(ba, count, uc);
                                continue;
                            }
                        }
                    } else {
                        // end of input character sequence
                        haveLeftoverChar = true;
                        leftoverChar = c;
                        break;
                    }
                }
                bp = count;
                handleMalformed();
                count = bp;
            } else {
                count = putThreeBytesChar(ba, count, c);
            }
        }

        bp = count;
    }

    /**
     * Grows ba to a capacity to allow len characters be encoded.
     */
    private void growByteBufferIfNeeded(int len) throws IOException {
        int cap = ba.length;
        if (cap < maxBufferCapacity) {
            int newCap = Math.min(len << 1, maxBufferCapacity);
            if (newCap > cap) {
                implFlushBuffer();
                ba = new byte[newCap];
                bb = null;
            }
        }
    }

    @Override
    protected void implFlushBuffer() throws IOException {
        int rem = bp;
        if (rem > 0) {
            bp = 0;
            if (ch != null) {
                if (bb == null) {
                    bb = ByteBuffer.wrap(ba, 0, rem);
                } else {
                    bb.clear();
                    bb.limit(rem);
                }

                int wc = ch.write(bb);
                assert wc == rem : rem;
            } else {
                out.write(ba, 0, rem);
            }
        }
    }

    @Override
    protected void implClose() throws IOException {
        try {
            if (haveLeftoverChar) {
                haveLeftoverChar = false;
                handleMalformed();
            }

            implFlushBuffer();
        } finally {
            if (ch != null) {
                ch.close();
            } else {
                out.close();
            }
        }
    }
}
