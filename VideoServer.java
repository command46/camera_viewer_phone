import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class VideoServer {
    private static final int PORT = 12346; // 视频服务使用的端口
    private static final String SAVE_PATH = "received_videos/"; // 保存视频的目录

    public static void main(String[] args) {
        // 创建保存目录
        File saveDir = new File(SAVE_PATH);
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("视频接收服务器启动，监听端口: " + PORT);

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("收到新的连接: " + clientSocket.getInetAddress());

                    // 为每个连接创建新线程处理
                    new Thread(() -> handleVideoConnection(clientSocket)).start();
                } catch (IOException e) {
                    System.err.println("处理连接时出错: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("服务器启动失败: " + e.getMessage());
        }
    }

    private static void handleVideoConnection(Socket socket) {
        try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            // 读取文件名
            String fileName = dis.readUTF();
            System.out.println("接收视频文件: " + fileName);

            // 生成保存路径
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String saveFileName = SAVE_PATH + timestamp + "_" + fileName;
            File videoFile = new File(saveFileName);

            // 接收视频文件
            try (FileOutputStream fos = new FileOutputStream(videoFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                long startTime = System.currentTimeMillis();

                while ((bytesRead = dis.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;

                    // 打印接收进度
                    if (totalBytes % (1024 * 1024) == 0) { // 每接收1MB打印一次
                        System.out.printf("已接收: %.2f MB\n", totalBytes / (1024.0 * 1024.0));
                    }
                }

                long endTime = System.currentTimeMillis();
                double duration = (endTime - startTime) / 1000.0;
                double speed = (totalBytes / (1024.0 * 1024.0)) / duration;

                System.out.printf("视频接收完成:\n");
                System.out.printf("文件名: %s\n", saveFileName);
                System.out.printf("文件大小: %.2f MB\n", totalBytes / (1024.0 * 1024.0));
                System.out.printf("用时: %.1f 秒\n", duration);
                System.out.printf("平均速度: %.2f MB/s\n", speed);
            }

        } catch (IOException e) {
            System.err.println("处理视频接收时出错: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("关闭连接时出错: " + e.getMessage());
            }
        }
    }
} 