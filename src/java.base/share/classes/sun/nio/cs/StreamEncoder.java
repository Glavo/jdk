/*
 * Copyright (c) 2001, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.InternalLock;

public abstract class StreamEncoder extends Writer {

    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private static final byte[] DEFAULT_UTF8_REPLACEMENT = {'?'};

    protected static final int INITIAL_BYTE_BUFFER_CAPACITY = 512;
    protected static final int MAX_BYTE_BUFFER_CAPACITY = 8192;

    private volatile boolean closed;

    protected final void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }

    // Factories for java.io.OutputStreamWriter
    public static StreamEncoder forOutputStreamWriter(OutputStream out,
                                                      Object lock,
                                                      String charsetName)
        throws UnsupportedEncodingException
    {
        try {
            return forOutputStreamWriter(out, lock, Charset.forName(charsetName));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException x) {
            throw new UnsupportedEncodingException (charsetName);
        }
    }

    public static StreamEncoder forOutputStreamWriter(OutputStream out,
                                                      Object lock,
                                                      Charset cs)
    {
        if (cs == StandardCharsets.UTF_8) {
            return JLA.newUTF8StreamEncoder(out, lock, CodingErrorAction.REPLACE, DEFAULT_UTF8_REPLACEMENT);
        }

        return new StreamEncoderImpl(out, lock, cs);
    }

    public static StreamEncoder forOutputStreamWriter(OutputStream out,
                                                      Object lock,
                                                      CharsetEncoder enc)
    {
        if (enc.charset() == StandardCharsets.UTF_8
            && enc.getClass().getModule() == Object.class.getModule()) {
            byte[] replacement = enc.malformedInputAction() == CodingErrorAction.REPLACE
                    ? enc.replacement() : null;
            return JLA.newUTF8StreamEncoder(out, lock, enc.malformedInputAction(), replacement);
        }

        return new StreamEncoderImpl(out, lock, enc);
    }


    // Factory for java.nio.channels.Channels.newWriter

    public static StreamEncoder forChannels(WritableByteChannel ch,
                                            Charset charset,
                                            int minBufferCap)
    {
        if (charset == StandardCharsets.UTF_8) {
            return JLA.newUTF8StreamEncoder(ch, CodingErrorAction.REPLACE, DEFAULT_UTF8_REPLACEMENT, minBufferCap);
        }

        return new StreamEncoderImpl(ch, charset.newEncoder(), minBufferCap);
    }


    public static StreamEncoder forChannels(WritableByteChannel ch,
                                            CharsetEncoder enc,
                                            int minBufferCap)
    {
        if (enc.charset() == StandardCharsets.UTF_8
            && enc.getClass().getModule() == Object.class.getModule()) {
            byte[] replacement = enc.malformedInputAction() == CodingErrorAction.REPLACE
                    ? enc.replacement() : null;
            return JLA.newUTF8StreamEncoder(ch, enc.malformedInputAction(), replacement, minBufferCap);
        }

        return new StreamEncoderImpl(ch, enc, minBufferCap);
    }


    // -- Public methods corresponding to those in OutputStreamWriter --

    // All synchronization and state/argument checking is done in these public
    // methods; the concrete stream-encoder subclasses defined below need not
    // do any such checking.

    public final String getEncoding() {
        if (isOpen())
            return encodingName();
        return null;
    }

    public final void flushBuffer() throws IOException {
        Object lock = this.lock;
        if (lock instanceof InternalLock locker) {
            locker.lock();
            try {
                lockedFlushBuffer();
            } finally {
                locker.unlock();
            }
        } else {
            synchronized (lock) {
                lockedFlushBuffer();
            }
        }
    }

    private void lockedFlushBuffer() throws IOException {
        ensureOpen();
        implFlushBuffer();
    }

    public final void write(char[] cbuf, int off, int len) throws IOException {
        Object lock = this.lock;
        if (lock instanceof InternalLock locker) {
            locker.lock();
            try {
                lockedWrite(cbuf, off, len);
            } finally {
                locker.unlock();
            }
        } else {
            synchronized (lock) {
                lockedWrite(cbuf, off, len);
            }
        }
    }

    private void lockedWrite(char[] cbuf, int off, int len) throws IOException {
        ensureOpen();
        Objects.checkFromIndexSize(off, len, cbuf.length);
        if (len == 0) {
            return;
        }
        implWrite(cbuf, off, len);
    }


    public final void flush() throws IOException {
        Object lock = this.lock;
        if (lock instanceof InternalLock locker) {
            locker.lock();
            try {
                lockedFlush();
            } finally {
                locker.unlock();
            }
        } else {
            synchronized (lock) {
                lockedFlush();
            }
        }
    }

    private void lockedFlush() throws IOException {
        ensureOpen();
        implFlush();
    }

    public final void close() throws IOException {
        Object lock = this.lock;
        if (lock instanceof InternalLock locker) {
            locker.lock();
            try {
                lockedClose();
            } finally {
                locker.unlock();
            }
        } else {
            synchronized (lock) {
                lockedClose();
            }
        }
    }

    private void lockedClose() throws IOException {
        if (closed)
            return;
        try {
            implClose();
        } finally {
            closed = true;
        }
    }

    private boolean isOpen() {
        return !closed;
    }


    // -- Charset-based stream encoder impl --

    private final Charset cs;

    // Exactly one of these is non-null
    protected final OutputStream out;
    protected final WritableByteChannel ch;

    // Leftover first char in a surrogate pair
    protected boolean haveLeftoverChar = false;
    protected char leftoverChar;

    protected StreamEncoder(OutputStream out, Object lock, Charset cs) {
        super(lock);
        this.out = out;
        this.ch = null;
        this.cs = cs;

    }

    protected StreamEncoder(WritableByteChannel ch, Charset cs) {
        this.out = null;
        this.ch = ch;
        this.cs = cs;
    }

    protected abstract void implWrite(char[] cbuf, int off, int len) throws IOException;

    protected abstract void implFlushBuffer() throws IOException;

    protected final void implFlush() throws IOException {
        implFlushBuffer();
        if (out != null) {
            out.flush();
        }
    }

    protected abstract void implClose() throws IOException;

    private String encodingName() {
        return ((cs instanceof HistoricallyNamedCharset)
                ? ((HistoricallyNamedCharset)cs).historicalName()
                : cs.name());
    }
}
