package ai4log.javaNginx2.javaNginx;//package ai4log.javaNginx2.javaNginx;

//import ai4log.javaNginxOtherReactor.NioSubReactor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

public class SubHttpServer implements Runnable {
    private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(10, 10, 1000, TimeUnit.SECONDS,  new LinkedBlockingDeque<Runnable>(50000));

    private static final int BUFFER_BYTE_SIZE = 1024;
//    public static final String DEFAULT_INDEX_HTML = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n学号，姓名<br><br>简易 HTTP 服务器";

    private static final String DEFAULT_INDEX_HTML_BODY = "学号，姓名<br><br>简易 HTTP 服务器";
    // HTTP响应需要设置一个Content-Length头部来告诉客户端消息体的长度，确保客户端正确读取终止
    private static final String DEFAULT_INDEX_HTML = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: "+DEFAULT_INDEX_HTML_BODY.getBytes(StandardCharsets.UTF_8).length+"\r\n\r\n"+DEFAULT_INDEX_HTML_BODY;

    private ServerSocketChannel serverSocketChannel;

    private Selector selector;

    private boolean running = false;

    private ThreadLocal<ByteBuffer> bufferThreadLocal = new ThreadLocal<>();

    public SubHttpServer() throws IOException {
        this.selector = Selector.open();
    }
//    private ExecutorService executor = Executors.newFixedThreadPool(500);
//    public void init(int port) throws IOException {
//        this.serverSocketChannel = ServerSocketChannel.open();
//        this.selector = Selector.open();
//        serverSocketChannel.configureBlocking(false);
//        serverSocketChannel.bind(new InetSocketAddress(port));
//        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
//    }

    @Override
    public void run() {
        start();
    }

    public void start() {
//        if (serverSocketChannel == null || selector == null) {
//            throw new RuntimeException("服务器未初始化");
//        }
        if (selector == null) {
            throw new RuntimeException("服务器未初始化");
        }

        if (running) {
            throw new RuntimeException("重复启动服务器");
        }
        this.running = true;
        Runnable main = () ->{
            while (running) {
                try {
                    selector.select(); //没有事件可选择时会阻塞,它仅在选择了至少一个通道后才会返回
                    Set<SelectionKey> selectionKeys = selector.selectedKeys();
                    Iterator<SelectionKey> iterator = selectionKeys.iterator();
                    while (iterator.hasNext()) {
                        SelectionKey selectionKey = iterator.next();
                        try {
                            // 判断该键是否对应的通道被关闭或者该键被显式地取消注册时
                            if (!selectionKey.isValid()) {
                                //
                                System.out.println("无效");
                                continue;
                            }
//                            if (selectionKey.isAcceptable()) {
//                                System.out.println("有可连接事件");
//                                SocketChannel channel = serverSocketChannel.accept();
//                                channel.socket().setTcpNoDelay(true);
//                                System.out.println("连接成功");
//                                channel.configureBlocking(false);
//                                channel.register(selector, SelectionKey.OP_READ);
//                                continue;
//                            }
                            if (selectionKey.isReadable()) {
                                selectionKey.interestOps(0);
                                try {
                                    new Handler(selectionKey).handleRead();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    try {
                                        selectionKey.channel().close();
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                    selectionKey.cancel();
                                }
                            }
                        } catch (CancelledKeyException e) {
                            System.out.println("selection已经关闭，不处理 error:" + e.getMessage());
                        } finally {
                            iterator.remove();
                        }

                    }

                } catch (IOException e) {
                    System.out.println("main error:" + e.getMessage());
//                    throw new RuntimeException(e);
                }

            }
        };
        executor.execute(main);
    }

    public void addScocketChannel(SocketChannel socketChannel) throws ClosedChannelException {
        // 唤醒选择器
//        selector.wakeup();
        // 主选择器传入的通道注册到子选择器， 使用子选择器处理事件
        SelectionKey selectionKey = socketChannel.register(selector, SelectionKey.OP_READ);
//        selectionKey.attach(new Handler(socketChannel));
//        wakeup();
    }

    public void wakeup() {
        // 唤醒因为选择器选择阻塞的方法

        selector.wakeup();
    }
    public void stop() {
        running = false;
        close();
    }

    private void close() {
        try {
            serverSocketChannel.close();
            selector.close();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private ByteBuffer getBufferFromThreadLocal() {
        ByteBuffer byteBuffer = bufferThreadLocal.get();
        if (byteBuffer == null) {
            byteBuffer = ByteBuffer.allocate(BUFFER_BYTE_SIZE);
            bufferThreadLocal.set(byteBuffer);
        }
        return byteBuffer;
    }

    private void clearBufferThreadLocal(ByteBuffer byteBuffer) {
        byteBuffer.clear();
    }

    /**
     * Channel data processing inner class
     *
     */
    class Handler implements Runnable {

        private SelectionKey selectionKey;

        public Handler(SelectionKey selectionKey) {
            this.selectionKey = selectionKey;
        }

        public void handleRead() throws IOException {
            try {
                dealRead();
            } catch (Exception e) {
                System.out.println("error:" + e.getMessage());
//                e.printStackTrace();
            }
        }

        private void dealRead() throws IOException {
            SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
            ByteBuffer buffer = getBufferFromThreadLocal();
            int read = socketChannel.read(buffer);

            if (read == -1) {
                socketChannel.close();
                selectionKey.cancel();
                clearBufferThreadLocal(buffer);
                return;
            } else {
                buffer.flip();
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                String request = new String(data, StandardCharsets.UTF_8);
                HttpRequestResolver httpRequestResolver = new HttpRequestResolver(request);
                String path = httpRequestResolver.getPath();

                if (path.equals("/index.html")) {
                    // Respond to the request
                    buffer.clear();
                    buffer.put(DEFAULT_INDEX_HTML.getBytes(StandardCharsets.UTF_8));
                    buffer.flip();
                    socketChannel.write(buffer);
                } else {
                    socketChannel.close();
                }
                buffer.clear();
            }

            // 重新注册为读事件
            selectionKey.interestOps(SelectionKey.OP_READ);
            selectionKey.selector().wakeup();
            buffer.clear();

            clearBufferThreadLocal(buffer);
        }

        @Override
        public void run() {
            try {
                handleRead();
            } catch (IOException e) {
                e.printStackTrace();
                try {
                    selectionKey.channel().close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
                selectionKey.cancel();
            }
        }
    }

//    public static void main(String[] args) {
//        try {
//            SubHttpServer server1 = new SubHttpServer();
//            server1.init(8080);
//            server1.start();
//        } catch (IOException e) {
//            e.printStackTrace();
//            throw new RuntimeException(e);
//        }
//    }

    public class HttpRequestResolver {
        private String method;

        private String path;

        private String version;

        private Map<String,String> headers = new HashMap<>();

        private String body;


        private boolean isLegal = false;

        public HttpRequestResolver(String request){
            if (request.trim().equals("")){
                return;
            }
            String[] lines = request.split("\n");
            //request line
            String[] item = lines[0].split(" ");
            if (item.length != 3){
                throw new RuntimeException("bad request \n" + request);
            }
            method = item[0].trim();
            path = item[1].trim();
            version = item[2].trim();
            //header
            for (int i = 1; i < lines.length; i++){
                if (lines[i] == null || lines[i].trim().equals("")){
                    break;
                }
                String[] header = lines[i].split(": ");
                if (header.length != 2){
                    throw new RuntimeException("bad request");
                }
                headers.put(header[0],header[1].trim());
            }
            //body
            String last = lines[lines.length - 1];
            body = last.trim().equals("") ? null : last;
            isLegal = true;
        }

        public String getMethod(){
            return method;
        }

        public String getPath(){
            return path;
        }

        public String getVersion(){
            return version;
        }

        public String getBody(){
            return body;
        }

        public String getHeader(String header){
            return headers.get(header);
        }


        public boolean isLegal(){
            return this.isLegal;
        }

    }


}
