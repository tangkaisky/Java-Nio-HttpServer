package ai4log.javaNginx2.javaNginx;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainHttpServer {
    private ServerSocketChannel serverSocketChannel;

    private Selector selector;

    private boolean running = false;

    // 当前机器环境中CPU核心数量
    private static int coreNum = Runtime.getRuntime().availableProcessors();

    private int subHttpServerNum = coreNum;

    private SubHttpServer[] subHttpServers = new SubHttpServer[subHttpServerNum];

    // 当前分配子处理器idx
    private int index = 0;

    private static final ExecutorService SUB_HTTP_SERVER_THREAD_POOL = Executors.newFixedThreadPool(coreNum);

    public void init(int port) throws IOException {
        this.serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.bind(new InetSocketAddress(port));
        this.selector = Selector.open();
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        for (int i = 0; i < subHttpServerNum; i++) {
            SubHttpServer subHttpServer = new SubHttpServer();
            subHttpServers[i] = subHttpServer;
            SUB_HTTP_SERVER_THREAD_POOL.execute(subHttpServer);
        }
    }

    public void start() {
        if (serverSocketChannel == null || selector == null) {
            throw new RuntimeException("服务器未初始化");
        }
        if (running) {
            throw new RuntimeException("重复启动服务器");
        }
        this.running = true;
        Runnable main = () -> {
            while (running) {
                try {
                    selector.select(); //没有事件可选择时会阻塞,它仅在选择了至少一个通道后才会返回
                    Set<SelectionKey> selectionKeys = selector.selectedKeys();
                    Iterator<SelectionKey> iterator = selectionKeys.iterator();
                    while (iterator.hasNext()) {
                        SelectionKey current = iterator.next();
                        try {
                            // 判断该键是否对应的通道被关闭或者该键被显式地取消注册时
                            if (!current.isValid()) {
                                //
                                System.out.println("无效");
                                continue;
                            }
                            if (current.isAcceptable()) {
//                                System.out.println("有可连接事件");
//                                SocketChannel channel = curServerSocketChannel.accept();
//                                channel.socket().setTcpNoDelay(true);
//                                System.out.println("连接成功");
//                                channel.configureBlocking(false);
//                                channel.register(selector, SelectionKey.OP_READ);

                                ServerSocketChannel curServerSocketChannel = (ServerSocketChannel) current.channel();
                                SocketChannel socketChannel = curServerSocketChannel.accept();
                                // no connection is available to be accepted
                                if (socketChannel == null ) {
                                    continue;
                                }
//                                System.out.println("连接成功");
                                socketChannel.socket().setTcpNoDelay(true);
                                socketChannel.configureBlocking(false);
                                // 采用轮询负载均衡策略，分发任务给子选择器处理
                                index = (index++) % coreNum;
                                SubHttpServer subHttpServer = subHttpServers[index];
                                subHttpServer.wakeup();
                                subHttpServer.addScocketChannel(socketChannel);
                                subHttpServer.wakeup();
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        } finally {
                            iterator.remove();
                        }

                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };

        main.run();
//        executor.execute(main);
    }

    public static void main(String[] args) {
        MainHttpServer server1 = new MainHttpServer();
        try {
            server1.init(8080);
            server1.start();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
