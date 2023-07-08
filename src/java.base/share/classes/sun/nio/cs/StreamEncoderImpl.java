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

package sun.nio.cs;

import jdk.internal.misc.InternalLock;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

final class StreamEncoderImpl extends StreamEncoder {

    // -- Public methods corresponding to those in OutputStreamWriter --

    // All synchronization and state/argument checking is done in these public
    // methods; the concrete stream-encoder subclasses defined below need not
    // do any such checking.

    public void write(int c) throws IOException {
        char[] cbuf = new char[1];
        cbuf[0] = (char) c;
        write(cbuf, 0, 1);
    }

    @Override
    public Writer append(CharSequence csq) throws IOException {
        if (csq instanceof CharBuffer) {
            write((CharBuffer) csq);
        } else {
            write(String.valueOf(csq));
        }
        return this;
    }

    private void write(CharBuffer cb) throws IOException {
        int position = cb.position();
        try {
            Object lock = this.lock;
            if (lock instanceof InternalLock locker) {
                locker.lock();
                try {
                    lockedWrite(cb);
                } finally {
                    locker.unlock();
                }
            } else {
                synchronized (lock) {
                    lockedWrite(cb);
                }
            }
        } finally {
            cb.position(position);
        }
    }

    private void lockedWrite(CharBuffer cb) throws IOException {
        ensureOpen();
        implWrite(cb);
    }

    public void write(String str, int off, int len) throws IOException {
        /* Check the len before creating a char buffer */
        if (len < 0)
            throw new IndexOutOfBoundsException();
        char[] cbuf = new char[len];
        str.getChars(off, off + len, cbuf, 0);
        write(cbuf, 0, len);
    }

    // -- Charset-based stream encoder impl --

    private final CharsetEncoder encoder;
    private CharBuffer lcb = null;

    private ByteBuffer bb;
    private final int maxBufferCapacity;

    StreamEncoderImpl(OutputStream out, Object lock, Charset cs) {
        this(out, lock,
                cs.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE));
    }

    StreamEncoderImpl(OutputStream out, Object lock, CharsetEncoder enc) {
        super(out, lock, enc.charset());
        this.encoder = enc;

        this.bb = ByteBuffer.allocate(INITIAL_BYTE_BUFFER_CAPACITY);
        this.maxBufferCapacity = MAX_BYTE_BUFFER_CAPACITY;
    }

    StreamEncoderImpl(WritableByteChannel ch, CharsetEncoder enc, int mbc) {
        super(ch, enc.charset());
        this.encoder = enc;

        if (mbc > 0) {
            this.bb = ByteBuffer.allocate(mbc);
            this.maxBufferCapacity = mbc;
        } else {
            this.bb = ByteBuffer.allocate(INITIAL_BYTE_BUFFER_CAPACITY);
            this.maxBufferCapacity = MAX_BYTE_BUFFER_CAPACITY;
        }
    }

    private void writeBytes() throws IOException {
        bb.flip();
        int lim = bb.limit();
        int pos = bb.position();
        assert (pos <= lim);
        int rem = (pos <= lim ? lim - pos : 0);

        if (rem > 0) {
            if (ch != null) {
                int wc = ch.write(bb);
                assert wc == rem : rem;
            } else {
                out.write(bb.array(), bb.arrayOffset() + pos, rem);
            }
        }
        bb.clear();
    }

    /**
     * Grows bb to a capacity to allow len characters be encoded.
     */
    private void growByteBufferIfNeeded(int len) throws IOException {
        int cap = bb.capacity();
        if (cap < maxBufferCapacity) {
            int maxBytes = len * Math.round(encoder.maxBytesPerChar());
            int newCap = Math.min(maxBytes, maxBufferCapacity);
            if (newCap > cap) {
                implFlushBuffer();
                bb = ByteBuffer.allocate(newCap);
            }
        }
    }

    private void flushLeftoverChar(CharBuffer cb, boolean endOfInput)
            throws IOException {
        if (!haveLeftoverChar && !endOfInput)
            return;
        if (lcb == null)
            lcb = CharBuffer.allocate(2);
        else
            lcb.clear();
        if (haveLeftoverChar)
            lcb.put(leftoverChar);
        if ((cb != null) && cb.hasRemaining())
            lcb.put(cb.get());
        lcb.flip();
        while (lcb.hasRemaining() || endOfInput) {
            CoderResult cr = encoder.encode(lcb, bb, endOfInput);
            if (cr.isUnderflow()) {
                if (lcb.hasRemaining()) {
                    leftoverChar = lcb.get();
                    if (cb != null && cb.hasRemaining()) {
                        lcb.clear();
                        lcb.put(leftoverChar).put(cb.get()).flip();
                        continue;
                    }
                    return;
                }
                break;
            }
            if (cr.isOverflow()) {
                assert bb.position() > 0;
                writeBytes();
                continue;
            }
            cr.throwException();
        }
        haveLeftoverChar = false;
    }

    private void implWrite(CharBuffer cb) throws IOException {
        if (haveLeftoverChar) {
            flushLeftoverChar(cb, false);
        }

        growByteBufferIfNeeded(cb.remaining());

        while (cb.hasRemaining()) {
            CoderResult cr = encoder.encode(cb, bb, false);
            if (cr.isUnderflow()) {
                assert (cb.remaining() <= 1) : cb.remaining();
                if (cb.remaining() == 1) {
                    haveLeftoverChar = true;
                    leftoverChar = cb.get();
                }
                break;
            }
            if (cr.isOverflow()) {
                assert bb.position() > 0;
                writeBytes();
                continue;
            }
            cr.throwException();
        }
    }

    protected void implWrite(char[] cbuf, int off, int len) throws IOException {
        CharBuffer cb = CharBuffer.wrap(cbuf, off, len);
        implWrite(cb);
    }

    protected void implFlushBuffer() throws IOException {
        if (bb.position() > 0) {
            writeBytes();
        }
    }

    protected void implClose() throws IOException {
        flushLeftoverChar(null, true);
        try {
            for (; ; ) {
                CoderResult cr = encoder.flush(bb);
                if (cr.isUnderflow())
                    break;
                if (cr.isOverflow()) {
                    assert bb.position() > 0;
                    writeBytes();
                    continue;
                }
                cr.throwException();
            }

            if (bb.position() > 0)
                writeBytes();
            if (ch != null)
                ch.close();
            else {
                try {
                    out.flush();
                } finally {
                    out.close();
                }
            }
        } catch (IOException x) {
            encoder.reset();
            throw x;
        }
    }
}
