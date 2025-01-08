// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.common;

import com.google.common.base.Stopwatch;
import com.starrocks.metric.MetricRepo;
import org.apache.thrift.TByteArrayOutputStream;
import org.apache.thrift.TConfiguration;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.server.ServerContext;
import org.apache.thrift.server.TServer;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TNonblockingServerTransport;
import org.apache.thrift.transport.TNonblockingSocket;
import org.apache.thrift.transport.TNonblockingTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.TTransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Provides common methods and classes used by nonblocking TServer
 * implementations.
 */
public abstract class SRAbstractNonblockingServer extends TServer {
    protected static final Logger LOGGER = LoggerFactory.getLogger(SRAbstractNonblockingServer.class);

    private static final int MAX_READ_BYTES = 512 * 1024;

    public abstract static class AbstractNonblockingServerArgs
            <T extends AbstractNonblockingServerArgs<T>> extends AbstractServerArgs<T> {

        public AbstractNonblockingServerArgs(TNonblockingServerTransport transport) {
            super(transport);
            transportFactory(new TTransportFactory());
        }
    }

    public SRAbstractNonblockingServer(AbstractNonblockingServerArgs args) {
        super(args);
    }

    /**
     * Begin accepting connections and processing invocations.
     */
    public void serve() {
        // start any IO threads
        if (!startThreads()) {
            return;
        }

        // start listening, or exit
        if (!startListening()) {
            return;
        }

        setServing(true);

        // this will block while we serve
        waitForShutdown();

        setServing(false);

        // do a little cleanup
        stopListening();
    }

    /**
     * Starts any threads required for serving.
     *
     * @return true if everything went ok, false if threads could not be started.
     */
    protected abstract boolean startThreads();

    /**
     * A method that will block until when threads handling the serving have been
     * shut down.
     */
    protected abstract void waitForShutdown();

    /**
     * Have the server transport start accepting connections.
     *
     * @return true if we started listening successfully, false if something went
     * wrong.
     */
    protected boolean startListening() {
        try {
            serverTransport_.listen();
            return true;
        } catch (TTransportException ttx) {
            LOGGER.error("Failed to start listening on server socket!", ttx);
            return false;
        }
    }

    /**
     * Stop listening for connections.
     */
    protected void stopListening() {
        serverTransport_.close();
    }

    /**
     * Perform an invocation. This method could behave several different ways -
     * invoke immediately inline, queue for separate execution, etc.
     *
     * @return true if invocation was successfully requested, which is not a
     * guarantee that invocation has completed. False if the request
     * failed.
     */
    protected abstract boolean requestInvoke(StreamBuffer streamBuffer);

    /**
     * An abstract thread that handles selecting on a set of transports and
     * {@link StreamBuffer FrameBuffers} associated with selected keys
     * corresponding to requests.
     */
    protected abstract class AbstractSelectThread extends Thread {
        protected Selector selector;

        // List of FrameBuffers that want to change their selection interests.
        protected final Set<StreamBuffer> selectInterestChanges = new HashSet<StreamBuffer>();

        public AbstractSelectThread() throws IOException {
            this.selector = SelectorProvider.provider().openSelector();
        }

        /**
         * If the selector is blocked, wake it up.
         */
        public void wakeupSelector() {
            selector.wakeup();
        }

        /**
         * Add FrameBuffer to the list of select interest changes and wake up the
         * selector if it's blocked. When the select() call exits, it'll give the
         * FrameBuffer a chance to change its interests.
         */
        public void requestSelectInterestChange(StreamBuffer streamBuffer) {
            synchronized (selectInterestChanges) {
                selectInterestChanges.add(streamBuffer);
            }
            // wakeup the selector, if it's currently blocked.
            selector.wakeup();
        }

        /**
         * Check to see if there are any FrameBuffers that have switched their
         * interest type from read to write or vice versa.
         */
        protected void processInterestChanges() {
            synchronized (selectInterestChanges) {
                for (StreamBuffer fb : selectInterestChanges) {
                    fb.changeSelectInterests();
                }
                selectInterestChanges.clear();
            }
        }

        /**
         * Do the work required to read from a readable client. If the frame is
         * fully read, then invoke the method call.
         */
        protected void handleRead(SelectionKey key) {
            StreamBuffer buffer = (StreamBuffer) key.attachment();
            int size = buffer.read();
            if (size < 0) {
                cleanupSelectionKey(key);
                return;
            }
            if (size > 0 && buffer.state == StreamBufferState.AWAITING_SUBMIT) {
                if (!requestInvoke(buffer)) {
                    cleanupSelectionKey(key);
                }
            }
        }

        /**
         * Let a writable client get written, if there's data to be written.
         */
        protected void handleWrite(SelectionKey key) {
            StreamBuffer buffer = (StreamBuffer) key.attachment();
            if (!buffer.write()) {
                cleanupSelectionKey(key);
            }
        }

        /**
         * Do connection-close cleanup on a given SelectionKey.
         */
        protected void cleanupSelectionKey(SelectionKey key) {
            // remove the records from the two maps
            StreamBuffer buffer = (StreamBuffer) key.attachment();
            if (buffer != null) {
                // close the buffer
                buffer.close();
            }
            // cancel the selection key
            key.cancel();
        }
    } // SelectThread

    /**
     * Possible states for the FrameBuffer state machine.
     */
    private enum StreamBufferState {
        // wait for first reading event
        AWAITING_READING,
        // submit task to executor thread pool
        AWAITING_SUBMIT,
        // read from socket and write to pipe
        READING_BUFFER,
        // waiting to get switched to listening for write events
        AWAITING_REGISTER_WRITE,
        // started writing response data, not fully complete yet
        WRITING,
        // another thread wants this framebuffer to go back to reading
        AWAITING_REGISTER_READ,
        // we want our transport and selection key invalidated in the selector
        AWAITING_CLOSE
    }

    /**
     * Class that implements a sort of state machine around the interaction with a
     * client and an invoker. It manages reading the frame size and frame data,
     * getting it handed off as wrapped transports, and then the writing of
     * response data back to the client. In the process it manages flipping the
     * read and write bits on the selection key for its client.
     */
    public class StreamBuffer {

        // the actual transport hooked up to the client.
        protected final TNonblockingTransport trans;

        // the SelectionKey that corresponds to our transport
        protected final SelectionKey selectionKey;

        // the SelectThread that owns the registration of our transport
        protected final AbstractSelectThread selectThread;

        // where in the process of reading/writing are we?
        protected volatile StreamBufferState state = StreamBufferState.AWAITING_READING;

        protected volatile ByteBuffer writeBuffer;

        protected final TByteArrayOutputStream response;

        // the transport that should be used to connect to clients
        protected final TStreamPipedTransport inTrans;

        protected final TTransport outTrans;

        // the input protocol to use on frames
        protected final TProtocol inProtocol;

        // the output protocol to use on frames
        protected final TProtocol outProtocol;

        // context associated with this connection
        protected final ServerContext context;

        public StreamBuffer(final TNonblockingTransport trans, final SelectionKey selectionKey,
                            final AbstractSelectThread selectThread) throws TTransportException {
            this.trans = trans;
            this.selectionKey = selectionKey;
            this.selectThread = selectThread;

            inTrans = new TStreamPipedTransport(((TNonblockingSocket) trans).getSocketChannel());
            response = new TByteArrayOutputStream();
            outTrans = outputTransportFactory_.getTransport(new TIOStreamTransport(response));
            inProtocol = inputProtocolFactory_.getProtocol(inTrans);
            outProtocol = outputProtocolFactory_.getProtocol(outTrans);

            if (eventHandler_ != null) {
                context = eventHandler_.createContext(inProtocol, outProtocol);
            } else {
                context = null;
            }
        }

        /**
         * Just switch state.
         */
        public void beginInvoke() {
            state = StreamBufferState.READING_BUFFER;
        }

        /**
         * Give this FrameBuffer a chance to read. The selector loop should have
         * received a read event for this FrameBuffer.
         *
         * @return bytes size read in buffer,
         * -1 if there was an error or the connection closed.
         */
        public int read() {

            // it is possible to fall through from the READING_FRAME_SIZE section
            // to READING_FRAME if there's already some frame data available once
            // READING_FRAME_SIZE is complete.

            if (state == StreamBufferState.AWAITING_READING || state == StreamBufferState.READING_BUFFER) {
                int size = internalRead();
                if (size > 0 && state == StreamBufferState.AWAITING_READING) {
                    state = StreamBufferState.AWAITING_SUBMIT;
                }
                return size;
            }

            // if we fall through to this point, then the state must be invalid.
            LOGGER.error("Read was called but state is invalid ({})", state);
            return -1;
        }

        /**
         * Give this FrameBuffer a chance to write its output to the final client.
         */
        public boolean write() {
            if (state == StreamBufferState.WRITING) {
                try {
                    if (trans.write(writeBuffer) < 0) {
                        return false;
                    }
                } catch (TTransportException e) {
                    LOGGER.warn("Got an IOException during write!", e);
                    return false;
                }

                // we're done writing. now we need to switch back to reading.
                if (writeBuffer.remaining() == 0) {
                    writeBuffer = null;
                    prepareRead();
                }
                return true;
            }

            LOGGER.error("Write was called, but state is invalid ({})", state);
            return false;
        }

        /**
         * Give this FrameBuffer a chance to set its interest to write, once data
         * has come in.
         */
        public void changeSelectInterests() {
            try {
                switch (state) {
                    case AWAITING_REGISTER_WRITE:
                        // set the OP_WRITE interest
                        selectionKey.interestOps(SelectionKey.OP_WRITE);
                        state = StreamBufferState.WRITING;
                        break;
                    case AWAITING_REGISTER_READ:
                        prepareRead();
                        break;
                    case AWAITING_CLOSE:
                        close();
                        selectionKey.cancel();
                        break;
                    default:
                        LOGGER.error("changeSelectInterest was called, but state is invalid ({})", state);
                }
            } catch (CancelledKeyException ignore) {
                // maybe channel closed
            }
        }

        /**
         * Shut the connection down.
         */
        public void close() {
            // if we're being closed due to an error, we might have allocated a
            // buffer that we need to subtract for our memory accounting.
            trans.close();
            // wakeup and close process task
            inTrans.close();
            if (eventHandler_ != null) {
                eventHandler_.deleteContext(context, inProtocol, outProtocol);
            }
        }

        /**
         * After the processor has processed the invocation, whatever thread is
         * managing invocations should call this method on this FrameBuffer so we
         * know it's time to start trying to write again. Also, if it turns out that
         * there actually isn't any data in the response buffer, we'll skip trying
         * to write and instead go back to reading.
         */
        public void responseReady() {
            if (inTrans.available()) {
                // input should reach end
                state = StreamBufferState.AWAITING_CLOSE;
            } else if (response.len() == 0) {
                // go straight to reading again. this was probably an oneway method
                state = StreamBufferState.AWAITING_REGISTER_READ;
                writeBuffer = null;
            } else {
                writeBuffer = ByteBuffer.wrap(response.get(), 0, response.len());

                // set state that we're waiting to be switched to write. we do this
                // asynchronously through requestSelectInterestChange() because there is
                // a possibility that we're not in the main thread, and thus currently
                // blocked in select(). (this functionality is in place for the sake of
                // the HsHa server.)
                state = StreamBufferState.AWAITING_REGISTER_WRITE;
            }
            requestSelectInterestChange();
        }

        /**
         * Actually invoke the method signified by this FrameBuffer.
         */
        public void invoke() {
            response.reset();
            try {
                if (eventHandler_ != null) {
                    eventHandler_.processContext(context, inTrans, outTrans);
                }
                Stopwatch stopwatch = Stopwatch.createStarted();
                processorFactory_.getProcessor(inTrans).process(inProtocol, outProtocol);
                stopwatch.stop();
                MetricRepo.HISTO_THRIFT_RPC_LATENCY.update(stopwatch.elapsed(TimeUnit.NANOSECONDS));
                responseReady();
                return;
            } catch (TException te) {
                LOGGER.warn("Exception while invoking!", te);
            } catch (Throwable t) {
                LOGGER.error("Unexpected throwable while invoking!", t);
            }
            // This will only be reached when there is a throwable.
            state = StreamBufferState.AWAITING_CLOSE;
            requestSelectInterestChange();
        }

        /**
         * Perform a read into buffer.
         *
         * @return bytes size read in buffer
         * -1 if there was an error or the connection closed.
         */
        private int internalRead() {
            try {
                int totalSize = 0;
                while (totalSize <= MAX_READ_BYTES) {
                    ByteBuffer readBuffer = ByteBuffer.allocate(64);
                    int readSize = trans.read(readBuffer);
                    if (readSize < 0) {
                        return -1;
                    } else if (readSize == 0) {
                        break;
                    } else {
                        totalSize += readSize;
                        inTrans.writeBuffer(readBuffer);
                    }
                }
                return totalSize;
            } catch (TTransportException e) {
                LOGGER.warn("Got an IOException in internalRead!", e);
                return -1;
            }
        }

        /**
         * We're done writing, so reset our interest ops and change state
         * accordingly.
         */
        private void prepareRead() {
            // we can set our interest directly without using the queue because
            // we're in the select thread.
            // reset input/output
            writeBuffer = null;
            inTrans.clear();
            selectionKey.interestOps(SelectionKey.OP_READ);
            // get ready for another go-around
            state = StreamBufferState.AWAITING_READING;
        }

        /**
         * When this FrameBuffer needs to change its select interests and execution
         * might not be in its select thread, then this method will make sure the
         * interest change gets done when the select thread wakes back up. When the
         * current thread is this FrameBuffer's select thread, then it just does the
         * interest change immediately.
         */
        protected void requestSelectInterestChange() {
            if (Thread.currentThread() == this.selectThread) {
                changeSelectInterests();
            } else {
                this.selectThread.requestSelectInterestChange(this);
            }
        }
    } // StreamBuffer

    public static class TStreamPipedTransport extends TTransport {
        private final BlockingQueue<byte[]> bufferQueue = new LinkedBlockingQueue<>();
        private byte[] current = null;
        private int pos;
        private int endPos;

        private volatile boolean closed = false;

        private final SocketChannel socketChannel;
        private int timeoutMs = 5000;

        public TStreamPipedTransport(SocketChannel socketChannel) {
            this.socketChannel = socketChannel;
            try {
                this.timeoutMs = socketChannel.socket().getSoTimeout();
            } catch (SocketException ignore) { }
        }

        public SocketChannel getSocketChannel() {
            return socketChannel;
        }

        public void reset() {
            current = null;
            pos = 0;
            endPos = 0;
        }

        public void reset(byte[] buf) {
            reset(buf, 0, buf.length);
        }

        public void reset(byte[] buf, int offset, int length) {
            this.current = buf;
            this.pos = offset;
            this.endPos = offset + length;
        }

        public boolean available() {
            return pos < endPos || !bufferQueue.isEmpty();
        }

        public void clear() {
            bufferQueue.clear();
            reset();
        }

        public void writeBuffer(ByteBuffer buffer) {
            int offset = buffer.arrayOffset();
            int position = buffer.position();
            int len = position - offset;
            try {
                if (len > 0) {
                    byte[] bytes = new byte[len];
                    System.arraycopy(buffer.array(), offset, bytes, 0, bytes.length);
                    bufferQueue.put(bytes);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        public boolean isOpen() {
            return !closed;
        }

        @Override
        public void open() throws TTransportException {

        }

        public void close() {
            closed = true;
            try {
                bufferQueue.put(new byte[0]);
            } catch (InterruptedException ignore) {
            }
        }

        /**
         * need wait util reach expect len, use socket timeout as queue poll timeout
         */
        public int read(byte[] buf, int off, int len) throws TTransportException {
            int reads = 0;
            try {
                while (reads < len) {
                    if (closed) {
                        throw new TTransportException("Transport is closed, maybe is disconnected");
                    }
                    if (current == null || getBytesRemainingInBuffer() == 0) {
                        byte[] newBuf = bufferQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
                        if (newBuf == null) {
                            throw new TTransportException(TTransportException.TIMED_OUT,
                                    "Timeout while waiting for new bytes, expect " + (len - reads));
                        }
                        reset(newBuf);
                    } else {
                        int bytesRemaining = getBytesRemainingInBuffer();
                        int amtToRead = Math.min(len - reads, bytesRemaining);
                        if (amtToRead > 0) {
                            System.arraycopy(current, pos, buf, off, amtToRead);
                            off += amtToRead;
                            consumeBuffer(amtToRead);
                            reads += amtToRead;
                        }
                    }
                }
            } catch (InterruptedException e) {
                throw new TTransportException("Thread interrupted", e);
            }
            return reads;
        }

        /**
         * Writes to the underlying output stream if not null.
         */
        public void write(byte[] buf, int off, int len) throws TTransportException {
            throw new TTransportException(TTransportException.NOT_OPEN, "Cannot write to piped transport");
        }

        @Override
        public byte[] getBuffer() {
            return current;
        }

        public int getBufferPosition() {
            return pos;
        }

        public int getBytesRemainingInBuffer() {
            return endPos - pos;
        }

        public void consumeBuffer(int len) {
            pos += len;
        }

        @Override
        public TConfiguration getConfiguration() {
            throw new UnsupportedOperationException("Unimplemented method 'getConfiguration'");
        }

        @Override
        public void updateKnownMessageSize(long size) throws TTransportException {
            throw new UnsupportedOperationException("Unimplemented method 'updateKnownMessageSize'");
        }

        @Override
        public void checkReadBytesAvailable(long numBytes) throws TTransportException {

        }
    }
}
