package netty.netty.study.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import netty.netty.study.client.handler.ChannelStatusMonitor;
import netty.netty.study.data.ConnectionTag;
import netty.netty.study.data.Messaging;
import netty.netty.study.utils.StackTraceUtils;
import org.springframework.util.Assert;

import java.net.InetSocketAddress;
import java.util.concurrent.*;

/**
 * Netty Tcp Client 기능을 제공합니다.
 * TODO : 추후 임의로 던지는 이벤트 메시지를 구조화하여 처리하도록 합니다.
 * TODO : ByteBuf ReferenceCount 관련된 내용을 정확하게 처리하도록 해야 합니다.
 */
public class TcpClient implements ChannelStatusListener {
    private final Bootstrap bootstrap = new Bootstrap();
    private final TcpClient.ConnectUntilSuccess connectUntilSuccess = new ConnectUntilSuccess();
    private final String eventLogFormat = "%s : %s, result : %s";
    private final TcpClient.UserTask userTask = new UserTask();
    private Channel channel;
    private ConnectionTag connectionTag;
    private boolean shouldRecoverConnect = true;

    // TODO : 꼭 한 번만 수행되어야 한다면 생성자로 옮기는 방향 검토!
    public void init(ChannelInitializer<?> channelInitializer) {
        final int connectTimeoutMillis = 3000;
        final int nThreads = 2;

        Assert.isNull(bootstrap.config().group(), "you have to call this function in postConstruct()");

        EventLoopGroup eventLoopGroup = new NioEventLoopGroup(nThreads);

        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(channelInitializer);
    }

    public void destroy() {
        try {
            if (channel != null && channel.eventLoop() != null) {
                channel.eventLoop().shutdownGracefully().sync();
            }
            connectUntilSuccess.stop();
            userTask.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean connect(ConnectionTag connectionTag) {
        Assert.state(!StackTraceUtils.getCallerFunc().contentEquals("postConstruct"), "you have to call connectUntilSuccess()");
        Assert.state(!StackTraceUtils.getCallerFunc().contentEquals("connect"), "you have to call connectUntilSuccess()");

        this.connectionTag = connectionTag;
        this.disconnect();
        return connectOnce(connectionTag);
    }

    public void connectUntilSuccess(ConnectionTag connectionTag) {
        this.connectionTag = connectionTag;
        this.disconnect();
        connectUntilSuccess.sync(connectionTag);
    }

    public Future<Void> beginConnectUntilSuccess(ConnectionTag connectionTag) {
        this.connectionTag = connectionTag;
        this.disconnect();
        return connectUntilSuccess.begin(connectionTag);
    }

    private synchronized boolean connectOnce(ConnectionTag connectionTag) {
        ChannelFuture channelFuture;
        try {
            channelFuture = bootstrap.connect(connectionTag.getIp(), connectionTag.getPort()).sync();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        if (!channelFuture.isSuccess()) {
            return false;
        }

        shouldRecoverConnect = true;
        channel = channelFuture.channel();

        channel.pipeline().addLast(new ChannelStatusMonitor(this));
        return true;
    }

    public void disconnect() {
        shouldRecoverConnect = false; // 명시적으로 연결을 끊는 경우 연결 복구 로직 OFF
        try {
            connectUntilSuccess.stop();
            stopUserTasks();
            if (channel != null) {
                channel.close().sync();
                channel = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 원격지로 메시지를 전송합니다. 전송에 대한 이벤트 로그를 남기지 않고 예외가 발생한 경우에만 이벤트 로그를 남깁니다.
     * @param message 전송 메시지
     * @return 전송 결과
     */
    public boolean send(Object message) {
        try {
            beginSend(message);
        } catch (Exception e) {
            String eventLog = String.format(eventLogFormat, "send", message, e.toString());
            Messaging.error(connectionTag.getEquipmentId(), eventLog);
            return false;
        }
        return true;
    }

    /**
     * 원격지로 메시지를 전송하고 이벤트 로그를 남깁니다.
     * @param message 전송 메시지
     * @return 전송 결과
     */
    public boolean sendAndLog(Object message) {
        ChannelFuture future;
        try {
            future = beginSend(message);
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    String eventLog = String.format(eventLogFormat, "send", message, future.isSuccess());
                    Messaging.info(connectionTag.getEquipmentId(), eventLog);
                }
            });
        } catch (Exception e) {
            String eventLog = String.format(eventLogFormat, "send", message, e.toString());
            Messaging.error(connectionTag.getEquipmentId(), eventLog);
            return false;
        }
        return true;
    }

    /**
     * send() 및 sendAndLog() 메쏘드에서 공통으로 호출하여 원격지로 메시지를 전송합니다.
     *
     * @param message 전송 메시지
     * @return 전송에 대한 Future 객체
     */
    private ChannelFuture beginSend(Object message) {
        Assert.notNull(connectionTag, "connectUntilSuccess() must be called before.");
        if (channel == null) {
            throw new NullPointerException("channel is null");
        }
        return channel.writeAndFlush(message);
    }

    public boolean beginUserTask(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return userTask.begin(task, initialDelay, period, unit);
    }

    public void stopUserTasks() {
        userTask.stop();
    }

    public boolean isActive() {
        if (channel == null) {
            return false;
        }
        return channel.isActive();
    }

    public InetSocketAddress getLocalAddress() {
        if (channel == null) {
            return null;
        }
        return (InetSocketAddress) channel.localAddress();
    }

    @Override
    public void channelActive() {
        Messaging.connected(connectionTag.getEquipmentId());
    }

    @Override
    public void channelInactive() {
        if (shouldRecoverConnect) {
            beginConnectUntilSuccess(this.connectionTag);
        }
        Messaging.disconnected(connectionTag.getEquipmentId());
    }

    @Override
    public void exceptionCaught(Throwable cause) {
        Messaging.error(connectionTag.getEquipmentId(), cause.toString());
    }

    /**
     * Channel 의 EventLoop 쓰레드를 통해 실행할 사용자 태스크를 처리합니다.
     */
    private class UserTask {
        private final CopyOnWriteArrayList<ScheduledFuture<?>> userTaskFutures = new CopyOnWriteArrayList<>();

        public boolean begin(Runnable task, long initialDelay, long period, TimeUnit unit) {
            if (channel == null) {
                Messaging.error(connectionTag.getEquipmentId(), "channel is null");
                return false;
            }
            ScheduledFuture<?> future = channel.eventLoop().scheduleAtFixedRate(task, initialDelay, period, unit);
            userTaskFutures.add(future);
            return true;
        }

        public void stop() {
            if (!userTaskFutures.isEmpty()) {
                userTaskFutures.forEach(future -> future.cancel(true));
                userTaskFutures.clear();
            }
        }
    }

    /**
     * 연결 성공할 때 까지 연결을 재시도하는 기능을 캡슐화합니다.
     * Netty 에서 제공하는 EventLoop 를 통하여 실행 시, I/O 작업에 영향을 끼치는 동기 함수 사용에 제약이 생겨 별도의 전용 쓰레드로 처리합니다.
     */
    private class ConnectUntilSuccess {
        /**
         * 연결 반복 실행
         */
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        /**
         * 비동기 연결 반복 실행에 대한 Future 객체
         */
        private Future<Void> future;
        /**
         * 연결 반복 실행 종료
         */
        private CountDownLatch cancelEvent;

        /**
         * 연결 반복 실행 동기화 수행
         *
         * @param connectionTag 연결 정보
         */
        public void sync(ConnectionTag connectionTag) {
            future = begin(connectionTag);
            try {
                future.get();
            } catch (InterruptedException interruptedException) {
                // do nothing
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /**
         * 연결 반복 실행 태스크 시작
         *
         * @param connectionTag
         * @return 연결 반복 실행에 대한 Future 객체
         * @see this.connectOnce()
         */
        public Future<Void> begin(ConnectionTag connectionTag) {
            cancelEvent = new CountDownLatch(1);
            future = executor.submit(() -> {
                boolean connected;
                do {
                    if (cancelEvent.await(100, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                    connected = TcpClient.this.connectOnce(connectionTag);
                } while (!connected);
                cancelEvent.countDown();
                return null; // TODO 이건 뭘 의미하는 거지?
            });
            return future;
        }

        /**
         * 연결 반복 실행 종료
         */
        public void stop() {
            if (cancelEvent != null && cancelEvent.getCount() != 0) {
                cancelEvent.countDown();
                try {
                    future.get();
                } catch (InterruptedException interruptedException) {
                    interruptedException.printStackTrace(); // TODO 추후 최적화 필요함
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
