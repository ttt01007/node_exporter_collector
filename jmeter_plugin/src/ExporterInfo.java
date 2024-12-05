import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

//import org.json.JSONObject;

public class ExporterInfo extends AbstractJavaSamplerClient {
    private static final Logger log = LogManager.getLogger(ExporterInfo.class);
    private static volatile Connection connection = null;
    private static ScheduledExecutorService scheduler;
    private AtomicInteger idCounter = new AtomicInteger(1);
    private String[] ips;
    private String taskName;

    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        arguments.addArgument("taskName", "123");
        arguments.addArgument("ip", "39.107.95.220");
        return arguments;
    }

    @Override
    public SampleResult runTest(JavaSamplerContext context) {
        SampleResult result = new SampleResult();
        result.sampleStart();

        try {
            String threadName = Thread.currentThread().getName();
            if (threadName.contains("setUp Thread Group")) {
                this.ips = context.getParameter("ip").contains(",") ? context.getParameter("ip").split(",") : new String[]{context.getParameter("ip")};
                this.taskName = context.getParameter("taskName");

                String url = "jdbc:mysql://39.107.95.220:3306/exporterinfo?useUnicode=true&characterEncoding=UTF-8";
                String user = "root";
                String password = "123456";

                connection = DriverManager.getConnection(url, user, password);
                log.info("MySQL 连接已打开");

                // 删除旧的记录
                String deleteSQL = "DELETE FROM server_detail_info WHERE task_name = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(deleteSQL)) {
                    pstmt.setString(1, taskName);
                    pstmt.executeUpdate();
                    log.info("已删除 task_name 为 " + taskName + " 的旧记录");
                }

                scheduler = Executors.newSingleThreadScheduledExecutor();
                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        if (connection != null && !connection.isClosed()) {
                            for (String ip : ips) {
                                String insertSQL = "INSERT INTO server_detail_info (task_name, ip, cpu, memory, io, network, reserved1, reserved2, reserved3, reserved4, reserved5) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                                try (PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
                                    int id = idCounter.getAndIncrement();
                                    pstmt.setString(1, taskName); // 任务名称
                                    pstmt.setString(2, ip.trim()); // IP 地址

                                    // 从 node_exporter 获取数据
                                    NodeMetrics metrics = fetchMetricsFromNodeExporter(ip.trim());

                                    pstmt.setDouble(3, metrics.cpuUsage);
                                    pstmt.setDouble(4, metrics.memoryUsage);
                                    pstmt.setDouble(5, metrics.ioPerSecond);
                                    pstmt.setDouble(6, metrics.networkBytesPerSecond);
                                    pstmt.setString(7, null); // 预留字段1
                                    pstmt.setString(8, null); // 预留字段2
                                    pstmt.setString(9, null); // 预留字段3
                                    pstmt.setString(10, null); // 预留字段4
                                    pstmt.setString(11, null); // 预留字段5

                                    pstmt.executeUpdate();
                                    log.info("插入一条记录到 server_detail_info 表中, id: " + id);
                                }
                            }
                        }
                        log.info("服务器信息已更新");
                    } catch (SQLException e) {
                        log.error("插入记录失败", e);
                        StackTraceElement stackTraceElement = e.getStackTrace()[0];
                        log.error("系统出错，错误信息: " + e.toString() + " at " + stackTraceElement.getClassName() + "." + stackTraceElement.getMethodName() + ":" + stackTraceElement.getLineNumber());
                    }
                }, 0, 1, TimeUnit.SECONDS);

                result.setResponseMessage("MySQL 连接已打开");
                result.setResponseCodeOK();
                result.setSuccessful(true); // 确保这个请求被视为成功
            } else if (threadName.contains("tearDown Thread Group")) {
                // 停止定时任务
                if (scheduler != null && !scheduler.isShutdown()) {
                    scheduler.shutdownNow();
                }

                // 调用存储过程 UpdateServerSummary
                if (connection != null && !connection.isClosed()) {
                    try (CallableStatement stmt = connection.prepareCall("{CALL UpdateServerSummary(?)}")) {
                        stmt.setString(1, taskName);
                        stmt.execute();
                        log.info("存储过程 UpdateServerSummary 调用成功");
                    } catch (SQLException e) {
                        log.error("调用存储过程 UpdateServerSummary 失败", e);
                        StackTraceElement stackTraceElement = e.getStackTrace()[0];
                        log.error("系统出错，错误信息: " + e.toString() + " at " + stackTraceElement.getClassName() + "." + stackTraceElement.getMethodName() + ":" + stackTraceElement.getLineNumber());
                    }

                    connection.close();
                    connection = null;
                    log.info("MySQL 连接已关闭");

                    result.setResponseMessage("MySQL 连接已关闭");
                    result.setResponseCodeOK();
                    result.setSuccessful(true); // 确保这个请求被视为成功
                } else {
                    log.warn("MySQL 连接已经关闭或未建立连接");
                    result.setResponseMessage("MySQL 连接已经关闭或未建立连接");
                    result.setResponseCodeOK();
                    result.setSuccessful(true); // 确保这个请求被视为成功
                }
            } else {
                log.warn("不是 Setup 或 Teardown 线程组中的取样器");
                result.setResponseMessage("不是 Setup 或 Teardown 线程组中的取样器");
                result.setResponseCodeOK();
                result.setSuccessful(true); // 确保这个请求被视为成功
            }

            result.sampleEnd();
        } catch (SQLException e) {
            log.error("操作 MySQL 连接失败", e);
            result.setResponseMessage("操作 MySQL 连接失败: " + e.getMessage());
            result.setResponseCode("500");
            result.setSuccessful(false);
            result.sampleEnd();
        }

        return result;
    }

    private NodeMetrics fetchMetricsFromNodeExporter(String ip) {
        NodeMetrics metrics = new NodeMetrics();
        try {
            String url = "http://" + ip + ":9100/metrics";
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            double idleTime = 0;
            double totalTime = 0;

            while ((line = in.readLine()) != null) {
                if (line.startsWith("node_cpu_seconds_total")) {
                    String[] parts = line.split("\\s+");
                    double value = Double.parseDouble(parts[1]);
                    totalTime += value;

                    if (line.contains("mode=\"idle\"")) {
                        idleTime += value;
                    }
                } else if (line.startsWith("node_memory_MemAvailable_bytes")) {
                    metrics.memoryAvailable = Double.parseDouble(line.split(" ")[1]);
                } else if (line.startsWith("node_memory_MemTotal_bytes")) {
                    metrics.memoryTotal = Double.parseDouble(line.split(" ")[1]);
                } else if (line.startsWith("node_disk_io_time_seconds_total")) {
                    metrics.ioTime += Double.parseDouble(line.split(" ")[1]);
                } else if (line.startsWith("node_network_receive_bytes_total")) {
                    metrics.networkReceive += Double.parseDouble(line.split(" ")[1]);
                } else if (line.startsWith("node_network_transmit_bytes_total")) {
                    metrics.networkTransmit += Double.parseDouble(line.split(" ")[1]);
                }
            }
            in.close();

            // 计算 CPU 利用率
            double usage = 1 - (idleTime / totalTime);
            metrics.cpuUsage = Math.round(usage * 10000.0) / 100.0; // 百分比保存小数两位

            // 计算内存利用率
            metrics.memoryUsage = (1 - (metrics.memoryAvailable / metrics.memoryTotal)) * 100;
            metrics.memoryUsage = Math.round(metrics.memoryUsage * 100.0) / 100.0; // 百分比保存小数两位

            // 计算 IO 每秒次数和网络每秒流量
            metrics.ioPerSecond = Math.round(metrics.ioTime * 100.0) / 100.0; // IO 保存小数两位
            metrics.networkBytesPerSecond = (metrics.networkReceive + metrics.networkTransmit) / (1024 * 1024); // 转换为 Mb/s
            metrics.networkBytesPerSecond = Math.round(metrics.networkBytesPerSecond * 100.0) / 100.0;
        } catch (Exception e) {
            log.error("从 node_exporter 获取数据失败", e);
        }
        return metrics;
    }

    private static class NodeMetrics {
        double cpuUsage = 0;
        double memoryAvailable = 0;
        double memoryTotal = 0;
        double memoryUsage = 0;
        double ioTime = 0;
        double ioPerSecond = 0;
        double networkReceive = 0;
        double networkTransmit = 0;
        double networkBytesPerSecond = 0;
    }

    @Override
    public void setupTest(JavaSamplerContext context) {
        super.setupTest(context);
        this.ips = context.getParameter("ip").contains(",") ? context.getParameter("ip").split(",") : new String[]{context.getParameter("ip")};
        this.taskName = context.getParameter("taskName");
    }

    @Override
    public void teardownTest(JavaSamplerContext context) {
        // 将 teardownTest 留空，因为所有清理逻辑都移到了 runTest 中
    }
}