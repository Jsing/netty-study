package netty.netty.study.client;

import lombok.extern.slf4j.Slf4j;
import netty.netty.study.client.initializer.ClientChannelInitializer;
import netty.netty.study.data.ConnectionTag;
import netty.netty.study.data.LastStatus;

import java.net.InetSocketAddress;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ClientService {

    private final LastStatus lastStatus = new LastStatus();
    private final TcpClient tcpClient = new TcpClient();

    public void init() {

        tcpClient.init(new ClientChannelInitializer(lastStatus, (ChannelStatusListener)tcpClient));
    }

    public void end() {
        tcpClient.destroy();
    }

    public LastStatus lastStatus() {

        return lastStatus;
    }

    public void connectUntilSuccess(ConnectionTag connectionTag) {
        tcpClient.connectUntilSuccess(connectionTag);
    }

    public Future beginConnectUntilSuccess(ConnectionTag connectionTag) {
        return tcpClient.beginConnectUntilSuccess(connectionTag);
    }

    public boolean connectOnce(ConnectionTag connectionTag) {
         return tcpClient.connect(connectionTag);
    }

    public void disconnect() {
        tcpClient.disconnect();
    }

    public boolean scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return tcpClient.beginUserTask(task, initialDelay, period, unit);
    }

    public boolean send(Object msg) {
        return tcpClient.send(msg);
    }

    public boolean isActive() {
        return tcpClient.isActive();
    }

    public InetSocketAddress getLocalAddress() {

        return tcpClient.getLocalAddress();
    }
}
