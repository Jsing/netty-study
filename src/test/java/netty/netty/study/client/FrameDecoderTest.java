package netty.netty.study.client;

import netty.netty.study.configure.ServerAddress;
import netty.netty.study.server.ServerService;
import netty.netty.study.server.TcpServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;


@SpringBootTest
@DisplayName("Frame Decoder")
public class FrameDecoderTest {
    final private int serverPort = 12345;
    final private String serverIp = "127.0.0.1";
    private TcpServer server;

    @BeforeEach
    void contextLoad() {

        try {
            server = new TcpServer(serverPort);
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @AfterEach
    void contextEnd() {
        server.end();
    }

    @Test
    @DisplayName("Message Boundary")
    void messageBoundary() throws Exception {

        // Ready
        ClientService clientService = new ClientService();
        clientService.init();

        boolean connected = clientService.connect(ServerAddress.getIp(), ServerAddress.getPort());

        connected = clientService.isActive();

        Assertions.assertEquals(true, connected);

        // 서버 접속 대기 
        server.waitForClient(clientService.getLocalAddress());

        // 서버에 접속한 클라이언트 서비스 객체 획득
        ServerService service = server.getServerService(clientService.getLocalAddress().toString());

        // 의도적으로 메시지 하나를 5초 간격으로 나누어 전송
        service.send("1.Incomplete half message");

        Thread.sleep(5000);

        service.send(" 2.complete half message\n");

        // 서버에서 메시지 수신할 수 있도록 일정 시간 대기
        Thread.sleep(3000);

        Assertions.assertEquals("1.Incomplete half message 2.complete half message",
                clientService.lastStatus().get());

    }

    @Test
    @DisplayName("transferContent")
    void transferContent() {

    }

}
