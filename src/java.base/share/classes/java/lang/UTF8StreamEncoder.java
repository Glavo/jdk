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
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class UTF8StreamEncoder extends StreamEncoder {

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

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

    UTF8StreamEncoder(OutputStream out, Object lock, CodingErrorAction malformedInputAction, byte[] replacement) {
        super(out, lock, StandardCharsets.UTF_8);

        this.malformedInputAction = malformedInputAction;
        this.replacement = replacement;
    }

    UTF8StreamEncoder(WritableByteChannel ch, CodingErrorAction malformedInputAction, byte[] replacement, int mbc) {
        super(ch, StandardCharsets.UTF_8, mbc > 0 ? Math.max(mbc, 4) : -1);
        this.malformedInputAction = malformedInputAction;
        this.replacement = replacement;
    }

    private static void putTwoBytesChar(ByteBuffer bb, char c) {
        bb.put((byte) (0xc0 | (c >> 6)));
        bb.put((byte) (0x80 | (c & 0x3f)));
    }

    private static void putThreeBytesChar(ByteBuffer bb, char c) {
        bb.put((byte) (0xe0 | ((c >> 12))));
        bb.put((byte) (0x80 | ((c >> 6) & 0x3f)));
        bb.put((byte) (0x80 | (c & 0x3f)));
    }

    private static void putFourBytesChar(ByteBuffer bb, int uc) {
        bb.put((byte) (0xf0 | ((uc >> 18))));
        bb.put((byte) (0x80 | ((uc >> 12) & 0x3f)));
        bb.put((byte) (0x80 | ((uc >> 6) & 0x3f)));
        bb.put((byte) (0x80 | (uc & 0x3f)));
    }

    private void putChar(char c) throws IOException {
        if (bb.remaining() < 4) {
            writeBytes();
        }

        if (this.haveLeftoverChar) {
            this.haveLeftoverChar = false;

            if (Character.isLowSurrogate(c)) {
                putFourBytesChar(bb, Character.toCodePoint(this.leftoverChar, c));
            } else {
                handleMalformed();
            }
        } else if (c < 0x80) {
            bb.put((byte) c);
        } else if (c < 0x800) {
            putTwoBytesChar(bb, c);
        } else if (Character.isSurrogate(c)) {
            if (Character.isHighSurrogate(c)) {
                this.haveLeftoverChar = true;
                this.leftoverChar = c;
            } else {
                handleMalformed();
            }
        } else {
            putThreeBytesChar(bb, c);
        }
    }

    private void handleMalformed() throws IOException {
        if (malformedInputAction == CodingErrorAction.REPLACE) {
            for (int i = 0; i < replacement.length; ) {
                if (!bb.hasRemaining()) {
                    writeBytes();
                }

                int n = Math.min(replacement.length - i, bb.remaining());
                bb.put(replacement, i, n);
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

        if (this.haveLeftoverChar) {
            this.haveLeftoverChar = false;
            handleMalformed();
        }

        int end = off + len;
        while (off < end) {
            int pos = StringCoding.countPositives(arr, off, end - off);
            while (pos > 0) {
                if (!bb.hasRemaining()) {
                    writeBytes();
                }

                int n = Math.min(bb.remaining(), pos);
                bb.put(arr, off, n);

                pos -= n;
                off += n;
            }

            while (off < end) {
                byte c = arr[off];
                if (c < 0) {
                    if (bb.remaining() < 2) {
                        writeBytes();
                    }

                    putTwoBytesChar(bb, (char) c);
                    off++;
                } else {
                    break; // break inner loop
                }
            }
        }
    }

    private void implWriteUTF16(Object arr, long offset, int len) throws IOException {
        growByteBufferIfNeeded(len);

        long end = offset + ((long) len << 1);

        for (; offset < end; offset += 2) {
            putChar(UNSAFE.getCharUnaligned(arr, offset));
        }
    }

    /**
     * Grows bb to a capacity to allow len characters be encoded.
     */
    private void growByteBufferIfNeeded(int len) throws IOException {
        int cap = bb.capacity();
        if (cap < maxBufferCapacity) {
            int newCap = Math.min(len << 1, maxBufferCapacity);
            if (newCap > cap) {
                writeBytes();
                bb = ByteBuffer.allocate(newCap);
            }
        }
    }

    protected void implClose() throws IOException {
        try {
            if (this.haveLeftoverChar) {
                this.haveLeftoverChar = false;
                handleMalformed();
            }

            writeBytes();
        } finally {
            if (ch != null) {
                ch.close();
            } else {
                out.close();
            }
        }
    }
}
