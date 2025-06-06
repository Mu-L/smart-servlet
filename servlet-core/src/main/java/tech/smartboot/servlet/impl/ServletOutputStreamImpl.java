/*
 *  Copyright (C) [2022] smartboot [zhengjunweimail@163.com]
 *
 *  企业用户未经smartboot组织特别许可，需遵循AGPL-3.0开源协议合理合法使用本项目。
 *
 *   Enterprise users are required to use this project reasonably
 *   and legally in accordance with the AGPL-3.0 open source agreement
 *  without special permission from the smartboot organization.
 */

package tech.smartboot.servlet.impl;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import tech.smartboot.feat.core.server.HttpResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * @author 三刀
 * @version V1.0 , 2020/10/19
 */
public class ServletOutputStreamImpl extends ServletOutputStream {
    protected static final AtomicIntegerFieldUpdater<ServletOutputStreamImpl> stateUpdater = AtomicIntegerFieldUpdater.newUpdater(ServletOutputStreamImpl.class, "state");
    private boolean committed = false;
    /**
     * buffer仅用于提供response.resetBuffer能力,commit之后即失效
     */
    private byte[] buffer;
    private long written;
    private byte[] cacheByte;
    private WriteListener writeListener;
    private volatile int state;
    private static final int FLAG_CLOSED = 1;
    private static final int FLAG_WRITE_STARTED = 1 << 1;
    private static final int FLAG_READY = 1 << 2;
    private static final int FLAG_DELEGATE_SHUTDOWN = 1 << 3;
    private static final int FLAG_IN_CALLBACK = 1 << 4;
    private final HttpServletRequestImpl request;
    private final HttpResponse response;
    private int bufferSize;

    public ServletOutputStreamImpl(HttpServletRequestImpl request, HttpResponse response) {
        this.request = request;
        this.response = response;
    }

    @Override
    public boolean isReady() {
        throw new UnsupportedOperationException();
//        return false;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        this.writeListener = writeListener;
        request.getInternalAsyncContext().start(() -> {
            try {
                writeListener.onWritePossible();
            } catch (IOException e) {
                writeListener.onError(e);
            }
        });
    }

    @Override
    public void write(int v) throws IOException {
        initCacheBytes();
        cacheByte[0] = (byte) v;
        write(cacheByte, 0, 1);
    }

    /**
     * 初始化8字节的缓存数值
     */
    private void initCacheBytes() {
        if (cacheByte == null) {
            cacheByte = new byte[8];
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (committed) {
            doWrite(b, off, len);
            written += len;
            return;
        }
        if (written == 0 && len > 0 && len < bufferSize) {
            buffer = new byte[bufferSize];
        }
        if (len < bufferSize - written - 1) {
            System.arraycopy(b, off, buffer, (int) written, len);
            written += len;
        } else {
            flushServletBuffer();
            doWrite(b, off, len);
            written += len;
        }
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    private void doWrite(byte[] b, int off, int len) throws IOException {
        if (writeListener == null || anyAreSet(state, FLAG_IN_CALLBACK)) {
            response.getOutputStream().write(b, off, len);
        } else {
            response.getOutputStream().write(b, off, len, bufferOutputStream -> {
                try {
                    setFlags(FLAG_IN_CALLBACK);
                    writeListener.onWritePossible();
                    clearFlags(FLAG_IN_CALLBACK);
                } catch (IOException e) {
                    writeListener.onError(e);
                }
            });
        }
        if (written == response.getContentLength()) {
            response.getOutputStream().flush();
        }
    }

    @Override
    public void close() throws IOException {
        response.getOutputStream().close();
    }

    @Override
    public void flush() throws IOException {
        if (response.getContentLength() > 0 && written == response.getContentLength()) {
            return;
        }
        flushServletBuffer();
        response.getOutputStream().flush();
    }

    public void flushServletBuffer() throws IOException {
        committed = true;
        if (buffer != null && written > 0) {
            doWrite(buffer, 0, (int) written);
        } else if (writeListener != null) {
            writeListener.onWritePossible();
        }
        buffer = null;
    }

    public boolean isCommitted() {
        return committed;
    }

    public void resetBuffer() {
        if (committed) {
            throw new IllegalStateException();
        }
        written = 0;
    }

    public long getWritten() {
        return written;
    }

    protected static boolean anyAreClear(int var, int flags) {
        return (var & flags) != flags;
    }

    protected void clearFlags(int flags) {
        int old;
        do {
            old = state;
        } while (!stateUpdater.compareAndSet(this, old, old & ~flags));
    }

    protected void setFlags(int flags) {
        int old;
        do {
            old = state;
        } while (!stateUpdater.compareAndSet(this, old, old | flags));
    }

    protected boolean anyAreSet(int var, int flags) {
        return (var & flags) != 0;
    }
}
