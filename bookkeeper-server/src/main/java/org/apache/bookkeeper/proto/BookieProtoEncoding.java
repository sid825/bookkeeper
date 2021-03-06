/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.proto;

import com.google.protobuf.InvalidProtocolBufferException;
import org.jboss.netty.buffer.ChannelBufferFactory;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;

import org.apache.bookkeeper.proto.BookieProtocol.PacketHeader;
import org.jboss.netty.handler.codec.oneone.OneToOneEncoder;
import org.jboss.netty.handler.codec.oneone.OneToOneDecoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BookieProtoEncoding {
    private final static Logger LOG = LoggerFactory.getLogger(BookieProtoEncoding.class);

    static final EnDecoder REQ_PREV3 = new RequestEnDeCoderPreV3();
    static final EnDecoder REP_PREV3 = new ResponseEnDeCoderPreV3();
    static final EnDecoder REQ_V3 = new RequestEnDecoderV3();
    static final EnDecoder REP_V3 = new ResponseEnDecoderV3();

    static interface EnDecoder {

        /**
         * Encode a <i>object</i> into channel buffer.
         *
         * @param object
         *          object.
         * @return encode buffer.
         * @throws Exception
         */
        public Object encode(Object object, ChannelBufferFactory factory) throws Exception;

        /**
         * Decode a <i>packet</i> into an object.
         *
         * @param packet
         *          received packet.
         * @return parsed object.
         * @throws Exception
         */
        public Object decode(ChannelBuffer packet) throws Exception;

    }

    static class RequestEnDeCoderPreV3 implements EnDecoder {
        @Override
        public Object encode(Object msg, ChannelBufferFactory bufferFactory)
                throws Exception {
            if (!(msg instanceof BookieProtocol.Request)) {
                return msg;
            }
            BookieProtocol.Request r = (BookieProtocol.Request)msg;
            if (r instanceof BookieProtocol.AddRequest) {
                BookieProtocol.AddRequest ar = (BookieProtocol.AddRequest)r;
                int totalHeaderSize = 4 // for the header
                    + BookieProtocol.MASTER_KEY_LENGTH; // for the master key
                ChannelBuffer buf = bufferFactory.getBuffer(totalHeaderSize);
                buf.writeInt(new PacketHeader(r.getProtocolVersion(), r.getOpCode(), r.getFlags()).toInt());
                buf.writeBytes(r.getMasterKey(), 0, BookieProtocol.MASTER_KEY_LENGTH);
                return ChannelBuffers.wrappedBuffer(buf, ar.getData());
            } else {
                assert(r instanceof BookieProtocol.ReadRequest);
                int totalHeaderSize = 4 // for request type
                    + 8 // for ledgerId
                    + 8; // for entryId
                if (r.hasMasterKey()) {
                    totalHeaderSize += BookieProtocol.MASTER_KEY_LENGTH;
                }

                ChannelBuffer buf = bufferFactory.getBuffer(totalHeaderSize);
                buf.writeInt(new PacketHeader(r.getProtocolVersion(), r.getOpCode(), r.getFlags()).toInt());
                buf.writeLong(r.getLedgerId());
                buf.writeLong(r.getEntryId());
                if (r.hasMasterKey()) {
                    buf.writeBytes(r.getMasterKey(), 0, BookieProtocol.MASTER_KEY_LENGTH);
                }

                return buf;
            }
        }

        @Override
        public Object decode(ChannelBuffer packet)
                throws Exception {
            PacketHeader h = PacketHeader.fromInt(packet.readInt());

            // packet format is different between ADDENTRY and READENTRY
            long ledgerId = -1;
            long entryId = BookieProtocol.INVALID_ENTRY_ID;
            byte[] masterKey = null;
            short flags = h.getFlags();

            ServerStats.getInstance().incrementPacketsReceived();

            switch (h.getOpCode()) {
            case BookieProtocol.ADDENTRY:
                // first read master key
                masterKey = new byte[BookieProtocol.MASTER_KEY_LENGTH];
                packet.readBytes(masterKey, 0, BookieProtocol.MASTER_KEY_LENGTH);

                ChannelBuffer bb = packet.duplicate();

                ledgerId = bb.readLong();
                entryId = bb.readLong();
                return new BookieProtocol.AddRequest(h.getVersion(), ledgerId, entryId,
                        flags, masterKey, packet.slice());
            case BookieProtocol.READENTRY:
                ledgerId = packet.readLong();
                entryId = packet.readLong();

                if ((flags & BookieProtocol.FLAG_DO_FENCING) == BookieProtocol.FLAG_DO_FENCING
                    && h.getVersion() >= 2) {
                    masterKey = new byte[BookieProtocol.MASTER_KEY_LENGTH];
                    packet.readBytes(masterKey, 0, BookieProtocol.MASTER_KEY_LENGTH);
                    return new BookieProtocol.ReadRequest(h.getVersion(), ledgerId, entryId, flags, masterKey);
                } else {
                    return new BookieProtocol.ReadRequest(h.getVersion(), ledgerId, entryId, flags);
                }
            }
            return packet;
        }
    }

    static class ResponseEnDeCoderPreV3 implements EnDecoder {
        @Override
        public Object encode(Object msg, ChannelBufferFactory bufferFactory)
                throws Exception {
            if (!(msg instanceof BookieProtocol.Response)) {
                return msg;
            }
            BookieProtocol.Response r = (BookieProtocol.Response)msg;
            ChannelBuffer buf = bufferFactory.getBuffer(24);
            buf.writeInt(new PacketHeader(r.getProtocolVersion(),
                                          r.getOpCode(), (short)0).toInt());
            buf.writeInt(r.getErrorCode());
            buf.writeLong(r.getLedgerId());
            buf.writeLong(r.getEntryId());

            ServerStats.getInstance().incrementPacketsSent();
            if (msg instanceof BookieProtocol.ReadResponse) {
                BookieProtocol.ReadResponse rr = (BookieProtocol.ReadResponse)r;
                if (rr.hasData()) {
                    return ChannelBuffers.wrappedBuffer(buf,
                            ChannelBuffers.wrappedBuffer(rr.getData()));
                } else {
                    return buf;
                }
            } else if (msg instanceof BookieProtocol.AddResponse) {
                return buf;
            } else {
                LOG.error("Cannot encode unknown response type {}", msg.getClass().getName());
                return msg;
            }
        }
        @Override
        public Object decode(ChannelBuffer buffer)
                throws Exception {
            final int rc;
            final long ledgerId, entryId;
            final PacketHeader header;

            header = PacketHeader.fromInt(buffer.readInt());
            rc = buffer.readInt();
            ledgerId = buffer.readLong();
            entryId = buffer.readLong();

            switch (header.getOpCode()) {
            case BookieProtocol.ADDENTRY:
                return new BookieProtocol.AddResponse(header.getVersion(), rc, ledgerId, entryId);
            case BookieProtocol.READENTRY:
                if (rc == BookieProtocol.EOK) {
                    return new BookieProtocol.ReadResponse(header.getVersion(), rc,
                                                           ledgerId, entryId, buffer.slice());
                } else {
                    return new BookieProtocol.ReadResponse(header.getVersion(), rc,
                                                           ledgerId, entryId);
                }
            default:
                return buffer;
            }
        }
    }

    static class RequestEnDecoderV3 implements EnDecoder {

        @Override
        public Object decode(ChannelBuffer packet) throws Exception {
            return BookkeeperProtocol.Request.parseFrom(new ChannelBufferInputStream(packet));
        }

        @Override
        public Object encode(Object msg, ChannelBufferFactory factory) throws Exception {
            BookkeeperProtocol.Request request = (BookkeeperProtocol.Request) msg;
            return ChannelBuffers.wrappedBuffer(request.toByteArray());
        }

    }

    static class ResponseEnDecoderV3 implements EnDecoder {

        @Override
        public Object decode(ChannelBuffer packet) throws Exception {
            return BookkeeperProtocol.Response.parseFrom(new ChannelBufferInputStream(packet));
        }

        @Override
        public Object encode(Object msg, ChannelBufferFactory factory) throws Exception {
            BookkeeperProtocol.Response response = (BookkeeperProtocol.Response) msg;
            return ChannelBuffers.wrappedBuffer(response.toByteArray());
        }

    }

    public static class RequestEncoder extends OneToOneEncoder {

        @Override
        protected Object encode(ChannelHandlerContext ctx, Channel channel, Object msg)
                throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Encode request {} to channel {}.", msg, channel);
            }
            if (msg instanceof BookkeeperProtocol.Request) {
                return REQ_V3.encode(msg, ctx.getChannel().getConfig().getBufferFactory());
            } else if (msg instanceof BookieProtocol.Request) {
                return REQ_PREV3.encode(msg, ctx.getChannel().getConfig().getBufferFactory());
            } else {
                LOG.error("Invalid request to encode to {}: {}", channel, msg.getClass().getName());
                return msg;
            }
        }
    }

    public static class RequestDecoder extends OneToOneDecoder {

        @Override
        protected Object decode(ChannelHandlerContext ctx, Channel channel, Object msg)
                throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Received request {} from channel {} to decode.", msg, channel);
            }
            if (!(msg instanceof ChannelBuffer)) {
                return msg;
            }
            ChannelBuffer buffer = (ChannelBuffer) msg;
            try {
                buffer.markReaderIndex();
                try {
                    return REQ_V3.decode(buffer);
                } catch (InvalidProtocolBufferException e) {
                    buffer.resetReaderIndex();
                    return REQ_PREV3.decode(buffer);
                }
            } catch (Exception e) {
                LOG.error("Failed to decode a request from {} : ", channel, e);
                throw e;
            }
        }
    }

    public static class ResponseEncoder extends OneToOneEncoder {

        @Override
        protected Object encode(ChannelHandlerContext ctx, Channel channel, Object msg)
                throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Encode response {} to channel {}.", msg, channel);
            }
            if (msg instanceof BookkeeperProtocol.Response) {
                return REP_V3.encode(msg, ctx.getChannel().getConfig().getBufferFactory());
            } else if (msg instanceof BookieProtocol.Response) {
                return REP_PREV3.encode(msg, ctx.getChannel().getConfig().getBufferFactory());
            } else {
                LOG.error("Invalid response to encode to {}: {}", channel, msg.getClass().getName());
                return msg;
            }
        }
    }

    public static class ResponseDecoder extends OneToOneDecoder {

        @Override
        protected Object decode(ChannelHandlerContext ctx, Channel channel, Object msg)
                throws Exception {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Received response {} from channel {} to decode.", msg, channel);
            }
            if (!(msg instanceof ChannelBuffer)) {
                return msg;
            }
            ChannelBuffer buffer = (ChannelBuffer) msg;
            try {
                buffer.markReaderIndex();
                try {
                    return REP_V3.decode(buffer);
                } catch (InvalidProtocolBufferException e) {
                    buffer.resetReaderIndex();
                    return REP_PREV3.decode(buffer);
                }
            } catch (Exception e) {
                LOG.error("Failed to decode a response from channel {} : ", channel, e);
                throw e;
            }
        }
    }
}
