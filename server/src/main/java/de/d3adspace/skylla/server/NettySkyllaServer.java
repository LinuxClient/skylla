package de.d3adspace.skylla.server;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import de.d3adspace.constrictor.netty.NettyUtils;
import de.d3adspace.skylla.commons.listener.CompositePacketListenerContainer;
import de.d3adspace.skylla.commons.listener.PacketListenerContainer;
import de.d3adspace.skylla.commons.listener.PacketListenerContainerFactory;
import de.d3adspace.skylla.commons.netty.NettyPacketInboundHandler;
import de.d3adspace.skylla.protocol.PacketCodec;
import de.d3adspace.skylla.protocol.Protocol;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import java.util.List;
import java.util.stream.Collectors;

public final class NettySkyllaServer implements SkyllaServer {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final PacketListenerContainer packetListenerContainer;
  private final String host;
  private final int port;
  private final Protocol protocol;

  private Channel channel;

  private NettySkyllaServer(
      PacketListenerContainer packetListenerContainer,
      String host,
      int port,
      Protocol protocol
  ) {
    this.packetListenerContainer = packetListenerContainer;
    this.host = host;
    this.port = port;
    this.protocol = protocol;
  }

  public static Builder newBuilder() {
    return new Builder("localhost", 8080, Protocol.empty(), Lists.newArrayList());
  }

  @Override
  public void start() {
    var bossGroup = NettyUtils.createBossGroup();
    var workerGroup = NettyUtils.createWorkerGroup();

    var channelInitializer = ServerChannelInitializer
        .forProtocol(protocol, packetListenerContainer);

    var serverSocketChannel = NettyUtils.getServerSocketChannel();
    var serverBootstrap = new ServerBootstrap()
        .group(bossGroup, workerGroup)
        .channel(serverSocketChannel)
        .childHandler(channelInitializer);

    var channelFuture = serverBootstrap.bind(host, port).syncUninterruptibly();
    channelFuture.addListener((ChannelFutureListener) future -> {
      boolean success = future.isSuccess();
      if (success) {
        logger.atInfo()
            .log("Server started successfully.");
      } else {
        logger.atSevere()
            .withCause(future.cause())
            .log("Server couldn't start");
      }
    });

    channel = channelFuture.channel();
  }

  @Override
  public boolean isRunning() {
    return channel != null && channel.isActive();
  }

  @Override
  public void stop() {
    if (channel == null) {
      return;
    }

    channel.close();
  }

  /**
   * Channel initializer for the server that will add a length field wrapped packet codec.
   */
  private static class ServerChannelInitializer extends ChannelInitializer<ServerSocketChannel> {

    private final Protocol protocol;
    private final PacketListenerContainer packetListenerContainer;

    private ServerChannelInitializer(
        Protocol protocol,
        PacketListenerContainer packetListenerContainer
    ) {
      this.protocol = protocol;
      this.packetListenerContainer = packetListenerContainer;
    }

    private static ChannelInitializer<ServerSocketChannel> forProtocol(
        Protocol protocol,
        PacketListenerContainer packetListenerContainer
    ) {
      Preconditions.checkNotNull(protocol);
      Preconditions.checkNotNull(packetListenerContainer);
      return new ServerChannelInitializer(protocol, packetListenerContainer);
    }

    @Override
    protected void initChannel(ServerSocketChannel serverSocketChannel) throws Exception {
      var pipeline = serverSocketChannel.pipeline();

      var packetCodec = PacketCodec.forProtocol(protocol);

      pipeline.addLast(new LengthFieldBasedFrameDecoder(32768, 4, 4));
      pipeline.addLast(packetCodec);
      pipeline.addLast(new LengthFieldPrepender(4));

      var inboundHandler = NettyPacketInboundHandler.withListenerContainer(packetListenerContainer);
      pipeline.addLast(inboundHandler);
    }
  }

  public static class Builder {

    private final List<Object> packetListenerInstances;
    private String serverHost;
    private int serverPort;
    private Protocol protocol;

    private Builder(String serverHost, int serverPort, Protocol protocol,
        List<Object> packetListenerInstances) {
      this.serverHost = serverHost;
      this.serverPort = serverPort;
      this.protocol = protocol;
      this.packetListenerInstances = packetListenerInstances;
    }

    /**
     * Set the server host to a certain domain or ip.
     *
     * @param serverHost The server host.
     * @return The builder instance.
     */
    public Builder withServerHost(String serverHost) {
      Preconditions.checkNotNull(serverHost);
      this.serverHost = serverHost;
      return this;
    }

    /**
     * Set the server port.
     *
     * @param serverPort The server port.
     * @return The builder instance.
     */
    public Builder withServerPort(int serverPort) {
      this.serverPort = serverPort;
      return this;
    }

    /**
     * Set the server protocol.
     *
     * @param protocol The protocol.
     * @return The protocol.
     */
    public Builder withProtocol(Protocol protocol) {
      Preconditions.checkNotNull(protocol);
      this.protocol = protocol;
      return this;
    }

    /**
     * Add a listener object.
     *
     * @param listenerInstance The listener instancen.
     * @return The builder instance.
     */
    public Builder withListener(Object listenerInstance) {
      packetListenerInstances.add(listenerInstance);
      return this;
    }

    /**
     * Build the server instance. This will first scan the given listener instances for appropriate
     * listener methods.
     *
     * @return The server instance.
     */
    public NettySkyllaServer build() {
      var packetListenerContainerFactory = PacketListenerContainerFactory.create();
      var packetListenerContainers = packetListenerInstances.stream()
          .map(packetListenerContainerFactory::fromListenerInstance).collect(
              Collectors.toList());

      var packetListenerContainer = CompositePacketListenerContainer
          .withListeners(packetListenerContainers);
      return new NettySkyllaServer(packetListenerContainer, serverHost, serverPort, protocol);
    }
  }
}
