package net.md_5.bungee.netty.decoders;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import java.util.List;

import lombok.*;
import net.md_5.bungee.netty.PacketWrapper;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.netty.Var;
import net.md_5.bungee.netty.encoders.PacketTranslatorEncoder;
import net.md_5.bungee.netty.encoders.Varint21LengthFieldPrepender;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.Vanilla;
import net.md_5.bungee.protocol.packet.DefinedPacket;
import net.md_5.bungee.protocol.skip.PacketReader;

/**
 * This class will attempt to read a packet from {@link PacketReader}, with the
 * specified {@link #protocol} before returning a new {@link ByteBuf} with the
 * copied contents of all bytes read in this frame.
 * <p/>
 * It is based on {@link ReplayingDecoder} so that packets will only be returned
 * when all needed data is present.
 */
public class DualProtocolPacketDecoder extends ReplayingDecoder<Void>
{

    @Getter
    @Setter
    private Protocol protocol;
    boolean initialized = false;
    @Getter
    boolean ver17 = false;

    public DualProtocolPacketDecoder(Protocol protocol) {
        this.protocol = protocol;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception
    {
        int  startIndex = in.readerIndex();
        // While we have enough data
        if ( !initialized ) {
            ver17 = identifyProtocol( in );
            if ( ver17 ) {
                PacketTranslatorDecoder trDecoder = new PacketTranslatorDecoder( protocol );
                ctx.pipeline().addBefore( PipelineUtils.PACKET_ENCODE_HANDLER, PipelineUtils.TRANSLATOR_DECODE_HANDLER, trDecoder );
                ctx.pipeline().addBefore( PipelineUtils.TRANSLATOR_DECODE_HANDLER, PipelineUtils.TRANSLATOR_ENCODE_HANDLER, new PacketTranslatorEncoder( trDecoder ) );
                ctx.pipeline().addBefore( PipelineUtils.TRANSLATOR_ENCODE_HANDLER, PipelineUtils.VARINT_ENCODE_HANDLER, new Varint21LengthFieldPrepender() );
                ctx.pipeline().flush();
            }
            initialized = true;
        }
        if ( !ver17 ) {
            while ( !ver17 )
            {
                // Store our start index
                startIndex = in.readerIndex();
                short packetId = in.readUnsignedByte();
                //  Run packet through framer
                DefinedPacket packet = protocol.read( packetId, in );
                // If we got this far, it means we have formed a packet, so lets grab the end index
                int endIndex = in.readerIndex();
                // Allocate a buffer big enough for all bytes we have read
                ByteBuf buf = in.copy( startIndex, endIndex - startIndex );
                // Checkpoint our state incase we don't have enough data for another packet
                checkpoint();
                // Store our decoded message
                out.add(new PacketWrapper(packet, buf));
            }
        } else {
            in.readerIndex( startIndex );
            final byte[] buf = new byte[ 3 ];
            for ( int i = 0; i < buf.length; i++ )
            {
                if ( !in.isReadable() )
                {
                    in.resetReaderIndex();
                    return;
                }

                buf[i] = in.readByte();
                if ( buf[i] >= 0 )
                {
                    int length = Var.readVarInt( Unpooled.wrappedBuffer(buf) );

                    if ( in.readableBytes() < length )
                    {
                        in.resetReaderIndex();
                        return;
                    } else
                    {
                        out.add( in.readBytes( length ) );
                        return;
                    }
                }
            }
        }
    }

    private boolean identifyProtocol(ByteBuf in) {
        int index = in.readerIndex();
        try {
            short packetId = in.readUnsignedByte();
            return packetId != 0x02 && packetId != 0xFE;
        } finally {
            in.readerIndex( index );
        }
    }
}
