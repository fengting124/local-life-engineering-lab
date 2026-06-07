package com.personalprojections.locallife.server.module.shop.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.personalprojections.locallife.server.module.shop.service.ShopCacheService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 订阅 {@code shop} 表的 MySQL binlog 变更事件，驱动 Redis 门店详情缓存失效——
 * 替代 {@code ShopService} 中"先删缓存 → 写 DB → 延迟 500ms 再删缓存"的延迟双删方案
 * （架构对比与选型理由见 {@code ShopService} 类注释「缓存一致性：从延迟双删到 Canal+binlog」）。
 *
 * <h2>对应的架构升级</h2>
 * <p>承接 {@code ShopService} 类注释「面试追问准备」中标记的目标——
 * 「Canal 是下一阶段升级方向」。设计上参考了 hmdp（黑马点评，经典 Java 后端教学项目，
 * 与本项目同属"本地生活/到店消费" O2O 领域）{@code CanalSubscriber} 的实现思路：
 * 单线程长轮询 {@code getWithoutAck} → 解析 {@code RowChange} → 按表名分发 → 失败 {@code rollback} 重试。
 *
 * <h2>工作原理：把 MySQL 伪装成它的"从库"</h2>
 * <pre>
 *   MySQL master ──binlog（行级变更日志）──▸ Canal Server（伪装成 MySQL slave，dump 协议拉取并解析）
 *                                                  │ TCP 协议推送解析好的 RowChange
 *                                                  ▼
 *                                  本监听器（CanalConnector，单独线程长轮询）
 *                                                  │ 按 tableName 分发，提取主键 id
 *                                                  ▼
 *                                  ShopCacheService.deleteShopDetail(shopId)
 * </pre>
 * <p>本应用只引入了瘦客户端 {@code canal.client}（通过 TCP 拉取 Server 已经解析好的事件），
 * 真正"伪装成 MySQL 从库、解析 binlog 二进制协议"的重活由独立部署的 Canal Server 承担
 * （见 {@code infra/docker-compose.dev.yml} 的 {@code canal-server} 服务及 MySQL 的 binlog 开启配置）——
 * 这正是 Canal 方案"业务代码与 binlog 解析彻底解耦"的核心价值：业务方只需要关心
 * "数据变了、该清缓存了"，不需要懂 MySQL 复制协议。
 *
 * <h2>为什么只处理 INSERT / UPDATE / DELETE，不处理 DDL（ALTER 等）</h2>
 * <p>{@code shop} 表结构变更是低频运维操作，不应该由在线缓存失效逻辑承担；
 * 而且 DDL 事件的 {@code RowChange} 不带行数据（{@code rowDatasList} 为空），
 * 没有 shopId 可提取，处理它毫无意义，过滤掉是正确而不是"图省事"。
 *
 * <h2>消费失败如何处理：rollback 重试 + TTL 兜底（双重安全网）</h2>
 * <ul>
 *   <li>单条 entry 解析/处理异常：整批 {@code rollback()}（回退到上次 ack 的位点），
 *       sleep 后重新拉取——Canal Server 会重新推送这批未确认的事件，不会丢失</li>
 *   <li>即使本监听器长时间宕机（重试耗尽 / 进程崩溃 / Canal Server 不可用），
 *       {@code ShopCacheService} 的 Redis 缓存本身有 30 分钟 TTL 兜底——
 *       缓存最多脏 30 分钟后自然过期重建，不会永久不一致。
 *       <b>Canal 不是"防止数据永久错误"的最后防线（TTL 才是），它的价值是把
 *       数据不一致的窗口从"最长 30 分钟"压缩到"百毫秒级"</b>，这与
 *       {@code OrderService} 中"MQ 延时消息是主链路、定时任务是兜底"的分层防御思路是一致的——
 *       让快速路径专注于"快"，把"绝对不能错"的责任留给慢但可靠的兜底机制</li>
 * </ul>
 *
 * <h2>为什么用单线程 + 长轮询，而不是多线程并发消费</h2>
 * <p>binlog 是严格有序的（同一行的 INSERT→UPDATE→DELETE 顺序不能错乱），
 * 多线程并发消费会破坏这个顺序，可能出现"先执行了 UPDATE 的缓存失效，
 * 后执行 INSERT 的缓存失效"这种乱序，反而引入新的不一致。
 * 单线程消费 + 幂等的"删缓存"操作（删多次和删一次效果相同）是最简单正确的选择。
 *
 * <h2>{@code canal.enabled} 开关的意义</h2>
 * <p>Canal Server 是一个需要独立部署、独立维护的中间件（依赖 MySQL 开启 binlog、
 * 配置 dump 账号等），不是"开箱即用"的。本地开发默认关闭（{@code canal.enabled=false}），
 * 此时退回到 {@code ShopService} 现有的延迟双删（两套机制不冲突，延迟双删本身也不依赖 Canal），
 * 只有显式启用并且 Canal Server 就绪时才会接管缓存失效职责——
 * 这也是"新基础设施上线应该可灰度、可回退"的体现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopCacheInvalidationListener {

    /** 订阅的表名（不含库名），与 MySQL DDL 中的表名保持一致 */
    private static final String SHOP_TABLE_NAME = "shop";

    /** 门店主键列名，用于从 binlog 行数据中提取 shopId */
    private static final String ID_COLUMN_NAME = "id";

    /** 拉取不到数据 / 处理失败时的退避等待 */
    private static final long EMPTY_BATCH_SLEEP_MS = 1_000L;
    private static final long ERROR_RETRY_SLEEP_MS = 5_000L;

    private final ShopCacheService shopCacheService;

    @Value("${canal.enabled:false}")
    private boolean enabled;

    @Value("${canal.server-host:localhost}")
    private String serverHost;

    @Value("${canal.server-port:11111}")
    private int serverPort;

    /** Canal instance 名称（对应 Server 端 conf/{destination}/instance.properties） */
    @Value("${canal.destination:local_life}")
    private String destination;

    @Value("${canal.username:}")
    private String username;

    @Value("${canal.password:}")
    private String password;

    /** 订阅过滤表达式：{@code 库名\\.表名}，只订阅 shop 表，减少无关事件的网络与解析开销 */
    @Value("${canal.subscribe-filter:local_life\\.shop}")
    private String subscribeFilter;

    /** 单批最多拉取的 binlog 事件数 */
    @Value("${canal.batch-size:100}")
    private int batchSize;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "shop-canal-listener");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running;

    /**
     * 应用启动时拉起后台订阅线程（如果启用）。
     *
     * <p>用单独的线程而不是阻塞主线程，是因为 {@code connector.getWithoutAck()}
     * 是长轮询阻塞调用——这是经典的"后台常驻消费者"模式，与
     * {@code OrderCloseConsumer} 这类由 RocketMQ SDK 托管线程的消费者不同，
     * Canal 的瘦客户端把"如何消费"完全交给业务方自己实现，所以需要手动管理线程生命周期。
     */
    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("[ShopCanal] canal.enabled=false，不订阅 binlog，沿用 ShopService 的延迟双删做缓存一致性");
            return;
        }
        running = true;
        executor.submit(this::consumeLoop);
        log.info("[ShopCanal] 已启动 binlog 订阅线程: {}:{}/{}, filter={}",
                serverHost, serverPort, destination, subscribeFilter);
    }

    /** 应用关闭时优雅停止订阅线程，避免连接泄漏。 */
    @PreDestroy
    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    /**
     * 消费主循环：连接 → 订阅 → 长轮询拉取 → 处理 → ack；异常则整体重连重试。
     *
     * <p>外层 {@code while(running)} 负责"连接断开后自动重连"，
     * 内层 {@code while(running)} 负责"正常消费循环"——
     * 两层循环职责分离：内层只管"消费"，外层只管"连接的生命周期"。
     */
    private void consumeLoop() {
        while (running) {
            CanalConnector connector = CanalConnectors.newSingleConnector(
                    new InetSocketAddress(serverHost, serverPort), destination, username, password);
            try {
                connector.connect();
                connector.subscribe(subscribeFilter);
                connector.rollback();
                log.info("[ShopCanal] 已连接 Canal Server 并订阅: {}", subscribeFilter);

                while (running) {
                    Message message = connector.getWithoutAck(batchSize);
                    long batchId = message.getId();
                    int size = message.getEntries().size();

                    if (batchId == -1 || size == 0) {
                        sleepQuietly(EMPTY_BATCH_SLEEP_MS);
                        continue;
                    }

                    try {
                        handleEntries(message.getEntries());
                        connector.ack(batchId);
                    } catch (Exception e) {
                        // 处理失败：回退到上次 ack 的位点，Canal Server 会重新推送这批事件，不会丢失
                        log.error("[ShopCanal] 处理 binlog 事件失败，rollback 重试: batchId={}", batchId, e);
                        connector.rollback();
                        sleepQuietly(ERROR_RETRY_SLEEP_MS);
                    }
                }
            } catch (Exception e) {
                log.error("[ShopCanal] 连接 Canal Server 失败，{} ms 后重连: {}:{}",
                        ERROR_RETRY_SLEEP_MS, serverHost, serverPort, e);
                sleepQuietly(ERROR_RETRY_SLEEP_MS);
            } finally {
                try {
                    connector.disconnect();
                } catch (Exception ignored) {
                    // 断开失败不影响重连，忽略
                }
            }
        }
        log.info("[ShopCanal] 订阅线程已停止");
    }

    /**
     * 解析一批 binlog 事件，过滤出 shop 表的行级数据变更（INSERT/UPDATE/DELETE），
     * 提取 shopId 并失效对应的 Redis 缓存。
     */
    private void handleEntries(List<CanalEntry.Entry> entries) throws Exception {
        for (CanalEntry.Entry entry : entries) {
            // TRANSACTIONBEGIN/TRANSACTIONEND/HEARTBEAT 等不携带行数据，直接跳过
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }
            if (!SHOP_TABLE_NAME.equalsIgnoreCase(entry.getHeader().getTableName())) {
                continue;
            }

            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            CanalEntry.EventType eventType = rowChange.getEventType();

            // DDL（ALTER/CREATE/...）不带行数据，没有 shopId 可提取，过滤掉（详见类注释）
            if (eventType != CanalEntry.EventType.INSERT
                    && eventType != CanalEntry.EventType.UPDATE
                    && eventType != CanalEntry.EventType.DELETE) {
                continue;
            }

            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                // DELETE 时行数据已经从 DB 消失，只有 beforeColumns 还保留着被删除前的值；
                // INSERT/UPDATE 则要拿变更后的最新值（afterColumns）
                List<CanalEntry.Column> columns = eventType == CanalEntry.EventType.DELETE
                        ? rowData.getBeforeColumnsList()
                        : rowData.getAfterColumnsList();

                Long shopId = extractShopId(columns);
                if (shopId == null) {
                    continue;
                }
                shopCacheService.deleteShopDetail(shopId);
                log.info("[ShopCanal] binlog 触发缓存失效: event={}, shopId={}", eventType, shopId);
            }
        }
    }

    private Long extractShopId(List<CanalEntry.Column> columns) {
        for (CanalEntry.Column column : columns) {
            if (ID_COLUMN_NAME.equals(column.getName()) && StringUtils.hasText(column.getValue())) {
                try {
                    return Long.valueOf(column.getValue());
                } catch (NumberFormatException e) {
                    log.warn("[ShopCanal] id 列解析失败: value={}", column.getValue(), e);
                    return null;
                }
            }
        }
        return null;
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
