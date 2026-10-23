package com.ojjudge.judge.impl;

import com.ojjudge.judge.LanguageEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

    /**
     * 判题容器池。
     *
     * <h3>为什么需要它</h3>
     * <p>每次 {@code docker run} 都要走一遍「创建容器 → 挂载 → 启动 → 销毁」，
     * 在 Windows 的 Docker Desktop 上这段固定开销大概 400~500ms，
     * 而真正跑一段 A+B 的 Java 程序只有一两百毫秒 —— 大部分时间花在起容器上。
     *
     * <p>池的做法是：为每个镜像预先起几个<b>常驻</b>容器
     * （{@code docker run -d ... sleep infinity}），判题时用
     * {@code docker exec} 进去跑命令，用完归还。这样省掉的就是那 400~500ms。
     *
     * <h3>预热（prewarm）</h3>
     * <p>光把容器起起来还不够 —— 容器里的工具链缓存是<b>冷</b>的。
     * Go 尤其明显：新容器要连着编译一遍标准库依赖图，实测冷编译 14 秒、热编译 4 秒，
     * 而冷编译额外的 fork 量正是早期把它搞崩的原因之一。
     * 所以预热 = 起容器 + 在容器里编译并运行一遍 hello world，把缓存填热。
 *
 * <h3>为什么挂载根目录而不是每次判题的临时目录</h3>
 * <p>{@code docker exec} 不能追加挂载卷，卷必须在 {@code docker run} 时就定好。
 * 所以池容器在<strong>创建时</strong>就把判题根目录 {@code judge.sandbox.work-dir}
 * 挂到容器内的 {@code /oj}，之后每次判题只是换一个子目录
 * （{@code docker exec -w /oj/<本次目录名>}），源码和输入输出都走这个共享卷来回传。
 *
 * <h3>安全边界（与一次性容器相比并没有放宽）</h3>
 * <ul>
 *   <li>{@code --network=none}    无网络</li>
 *   <li>{@code --memory / --memory-swap}  内存上限，OOM 会被 kill</li>
 *   <li>{@code --pids-limit}      防 fork 炸弹</li>
 *   <li>判题命令仍由容器内的看门狗限时（见 DockerCodeSandbox 生成的 run.sh）</li>
 * </ul>
 * <p>唯一新增的风险是<b>容器复用可能留下残留</b>（上一次判题写的文件、没退干净的子进程）。
 * 因此每次归还前沙箱会删掉本次的临时目录，看门狗也会把用户进程 kill 掉。
 *
 * <h3>池不可用时怎么办</h3>
 * <p>借不到容器（池满 / Docker 没起 / 创建失败）就返回 {@code null}，
 * 沙箱自动退回一次性容器。也就是说池是<b>纯加速优化</b>，不是判题的前置依赖。
 */
@Component
@Slf4j
public class ContainerPool implements DisposableBean {

    /**
     * 容器内挂载点。与 {@link DockerCodeSandbox#MOUNT_POINT} 必须一致。
     */
    private static final String MOUNT_POINT = "/oj";

    private static final String NAME_PREFIX = "oj-pool-";

    /**
     * 是否启用容器池。关掉就是每次判题都起一次性容器。
     */
    @Value("${judge.sandbox.pool.enabled:true}")
    private boolean enabled;

    /**
     * 每个镜像最多常驻几个容器。
     *
     * <p>不需要很大：判题线程池（judgeExecutor）最大 4，
     * 超过并发数的容器也只会闲着占内存。
     */
    @Value("${judge.sandbox.pool.size:2}")
    private int size;

    /**
     * 池容器的内存上限（MB）。
     *
     * <p>注意：{@code docker exec} 没法改内存限制，所以池容器只能用这个硬上限，
     * 不能按题目逐条设置。题目配的上限更小也没关系 —— 沙箱会实测峰值内存再判超限。
     * 这里用的是编译阶段的预算（比运行阶段大），因为池容器同时承担编译和运行。
     */
    @Value("${judge.sandbox.compile-memory-mb:512}")
    private long maxMemoryMb;

    /**
     * CPU 核数上限。同样是创建时就定死的，{@code docker exec} 改不了。
     */
    @Value("${judge.sandbox.max-cpus:1}")
    private double maxCpus;

    /**
     * 进程 + 线程数上限。
     *
     * <p>必须给足：这个值数的是 cgroup 的 task 数（线程也算），
     * Go 构建时十几个并发 {@code compile} 子进程很容易顶上去 ——
     * 顶破之后所有 fork 都返回 EAGAIN，表现为「Go 怎么提交都过不了」。
     */
    @Value("${judge.sandbox.pids-limit:512}")
    private long pidsLimit;

    /**
     * 是否在应用启动后就异步把容器建好并预热好。
     *
     * <p>关掉的话第一次提交才会创建（也就是第一次判题明显更慢）。
     */
    @Value("${judge.sandbox.pool.prewarm:true}")
    private boolean prewarm;

    /**
     * 判题根目录，池容器在创建时把它挂进容器。与沙箱读的是同一个配置。
     */
    @Value("${judge.sandbox.work-dir:}")
    private String workDirRoot;

    /** 镜像 key → 空闲容器名 */
    private final ConcurrentHashMap<String, LinkedBlockingQueue<String>> idle = new ConcurrentHashMap<>();

    /** 镜像 key → 已创建的容器数 */
    private final ConcurrentHashMap<String, AtomicInteger> createdCount = new ConcurrentHashMap<>();

    /** 池创建过的全部容器名，进程退出时统一回收 */
    private final Set<String> allNames = ConcurrentHashMap.newKeySet();

    /**
     * 借一个该镜像的常驻容器。借不到返回 {@code null}（调用方应退回一次性容器）。
     */
    public PooledContainer borrow(LanguageEnum language) {
        if (!enabled || language == null) {
            return null;
        }
        String key = keyOf(language);

        LinkedBlockingQueue<String> queue = idle.computeIfAbsent(key, k -> new LinkedBlockingQueue<>());
        String existing = queue.poll();
        if (existing != null) {
            if (isRunning(existing)) {
                return new PooledContainer(existing, key);
            }
            // 容器已经没了（被手动删、Docker 重启过），丢掉重建
            log.info("池容器已失效，销毁重建：{}", existing);
            destroy(existing);
            createdCount.computeIfAbsent(key, k -> new AtomicInteger()).decrementAndGet();
        }

        AtomicInteger counter = createdCount.computeIfAbsent(key, k -> new AtomicInteger());
        if (counter.incrementAndGet() > Math.max(1, size)) {
            counter.decrementAndGet();
            return null;                        // 池满：这一轮就用一次性容器
        }

        String name = create(language, key);
        if (name == null) {
            counter.decrementAndGet();
            return null;
        }
        return new PooledContainer(name, key);
    }

    /**
     * 归还容器。已经死掉的会被销毁，下次借的时候重新建。
     */
    public void release(PooledContainer container) {
        if (container == null) {
            return;
        }
        if (isRunning(container.name())) {
            // 先把上一次判题可能残留的进程清掉，再放回池里。
            // 不清的话 pids 配额会被一点点占满，攒几次之后新判题就 fork 不动了
            killStrayProcesses(container.name());
            idle.computeIfAbsent(container.key(), k -> new LinkedBlockingQueue<>()).offer(container.name());
        } else {
            destroy(container.name());
            createdCount.computeIfAbsent(container.key(), k -> new AtomicInteger()).decrementAndGet();
        }
    }

    /**
     * 销毁一个池容器（比如它在判题中途挂了），并让计数回退以便下次重建。
     */
    public void discard(PooledContainer container) {
        if (container == null) {
            return;
        }
        destroy(container.name());
        createdCount.computeIfAbsent(container.key(), k -> new AtomicInteger()).decrementAndGet();
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 起一个常驻容器：不跑判题命令，只 {@code sleep infinity} 等 {@code docker exec}。
     */
    private String create(LanguageEnum language, String key) {
        String name = NAME_PREFIX + key + "-" + UUID.randomUUID().toString().substring(0, 6);
        try {
            Path root = resolveRoot();
            Path cache = Files.createDirectories(root.resolve(DockerCodeSandbox.CACHE_DIR_NAME));
            Process process = new ProcessBuilder("docker", "run", "-d",
                    "--network=none",
                    "--memory=" + maxMemoryMb + "m",
                    "--memory-swap=" + maxMemoryMb + "m",
                    "--pids-limit=" + pidsLimit,
                    "--cpus=" + maxCpus,
                    "--name", name,
                    "-v", mountArg(root, MOUNT_POINT),
                    "-v", mountArg(cache, DockerCodeSandbox.CACHE_MOUNT_POINT),
                    "-w", MOUNT_POINT,
                    "-e", "HOME=/tmp",
                    language.getImage(),
                    "sleep", "infinity")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.error("创建池容器超时：{}", name);
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                log.error("创建池容器失败({})：{}", name, output.trim());
                return null;
            }
            allNames.add(name);
            log.info("已创建判题池容器：{} ({})", name, language.getImage());
            return name;
        } catch (IOException e) {
            log.error("创建池容器失败，将退回一次性容器", e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 容器是否还活着。
     *
     * <p>每次借用前都要确认：容器可能被人手动删掉、也可能因为 Docker Desktop
     * 重启而消失。不检查的话 {@code docker exec} 会直接报错，判题就失败了。
     */
    private boolean isRunning(String name) {
        try {
            Process process = new ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", name)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return "true".equalsIgnoreCase(output.trim());
        } catch (Exception e) {
            return false;
        }
    }

    private void destroy(String name) {
        allNames.remove(name);
        try {
            Process process = new ProcessBuilder("docker", "rm", "-f", name)
                    .redirectErrorStream(true)
                    .start();
            process.waitFor(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("销毁池容器失败：{}", name);
        }
    }

    /**
     * 判题根目录。配了 {@code judge.sandbox.work-dir} 就用它，
     * 否则用系统临时目录下的 {@code oj-judge} 子目录。
     *
     * <p>必须与 { DockerCodeSandbox#resolveRoot()} 完全一致 ——
     * 两边算出不同路径的话，池容器挂载的和沙箱写文件的就不是同一个目录，
     * 判题会找不到源码。
     */
    private Path resolveRoot() throws IOException {
        Path root = StringUtils.hasText(workDirRoot)
                ? Paths.get(workDirRoot.trim())
                : Paths.get(System.getProperty("java.io.tmpdir"), "oj-judge");
        Files.createDirectories(root);
        return root;
    }

    /**
     * 宿主机路径 → {@code docker -v} 认得的形式。
     * Windows 的 {@code C:\...} 转成 {@code /c/...}，Linux 原样返回。
     */
    private String mountArg(Path hostPath, String target) {
        String path = hostPath.toString().replace('\\', '/');
        if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
            return "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2) + ":" + target;
        }
        return path + ":" + target;
    }

    // ------------------------------------------------------------------ 预热

    /**
     * 应用启动完成后异步预热容器池。
     *
     * <p>为什么要等 {@code ApplicationReadyEvent}：预热可能要好几秒（起容器 + 编译），
     * 卡在这里会让启动变慢，Docker 没起来时还会拖累整个应用。
     * 所以丢到后台线程里跑，失败也只记日志 —— 最坏结果只是第一次判题慢一点。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void prewarmAsync() {
        if (!enabled || !prewarm) {
            return;
        }
        Thread thread = new Thread(this::prewarmAll, "judge-pool-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 给每个语言建满池容器，并逐个跑一遍 hello world 把工具链缓存填热。
     */
    private void prewarmAll() {
        log.info("开始预热判题容器池：每语言 {} 个", size);
        for (LanguageEnum language : LanguageEnum.values()) {
            for (int i = 0; i < Math.max(1, size); i++) {
                PooledContainer container = borrow(language);
                if (container == null) {
                    // 容器建不出来（Docker 没起 / 镜像没拉），后面也不用再试了
                    log.warn("预热中断：{} 的容器创建失败，首次判题会慢一些", language.getValue());
                    break;
                }
                warmUp(language, container.name());
                release(container);
            }
        }
        log.info("判题容器池预热完成");
    }

    /**
     * 在一个容器里编译并运行一遍 hello world。
     *
     * <p>这一步的意义是把工具链缓存（Go 的 build cache 尤其明显）留在这个容器里。
     * 预热目录刻意留在判题根目录下不删 —— 下次再跑又是热的。
     */
    private void warmUp(LanguageEnum language, String containerName) {
        String dirName = ".warmup-" + language.getValue();
        try {
            Path root = resolveRoot();
            Path dir = Files.createDirectories(root.resolve(dirName));
            Files.writeString(dir.resolve(language.getFileName()),
                    language.getWarmUpSource(), StandardCharsets.UTF_8);

            String workDir = MOUNT_POINT + "/" + dirName;
            execQuiet(containerName, workDir, language.getCompileCommand());
            // 运行一遍：stdin 给 /dev/null，别让它等着读输入
            execQuiet(containerName, workDir,
                    language.getRunCommand() + " < /dev/null > /dev/null 2>&1");
            log.debug("已预热 {} 容器 {}", language.getValue(), containerName);
        } catch (Exception e) {
            // 预热失败不影响判题，只是第一次会慢
            log.warn("预热 {} 失败（不影响判题，首次判题会慢一些）", language.getValue(), e);
        }
    }

    /**
     * 在容器里执行一条命令，忽略结果（预热用）。
     */
    private void execQuiet(String containerName, String workDir, String command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "exec", "-w", workDir,
                containerName, "sh", "-c", command)
                .redirectErrorStream(true)
                .start();
        // 预热可以慢，但不能无限等
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            log.warn("预热命令超时：{}", command);
            return;
        }
        if (process.exitValue() != 0) {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.warn("预热命令返回非零：{} -> {}", command, output.trim());
        }
    }

    // ------------------------------------------------------------------ 清理

    /**
     * 归还容器前，把里面残留的进程清干净。
     *
     * <p>为什么需要：容器是<b>复用</b>的，上一次判题万一留下了没退干净的东西
     * （逃脱了看门狗的子进程、用户程序 fork 出来的后台任务），会一直占着
     * pids 配额。攒几次之后新的判题就会 fork 失败。
     *
     * <p>跳过 pid 1（那是我们跑的 {@code sleep infinity}）和当前 exec 自己的 pid。
     */
    private void killStrayProcesses(String name) {
        String script = "for d in /proc/[0-9]*; do p=${d#/proc/};"
                + " case $p in 1|$$) continue;; esac;"
                + " kill -9 \"$p\" 2>/dev/null; done; true";
        try {
            Process process = new ProcessBuilder("docker", "exec", name, "sh", "-c", script)
                    .redirectErrorStream(true)
                    .start();
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("清理容器内残留进程失败：{}", name);
        }
    }

    /**
     * 容器名用的 key：镜像名里的冒号、斜杠都不能出现在容器名里。
     */
    private String keyOf(LanguageEnum language) {
        return language.getImage().replaceAll("[^a-zA-Z0-9]", "-").toLowerCase();
    }

    /**
     * 进程退出时把所有池容器清掉，否则会留下一堆 {@code sleep infinity} 容器常驻。
     */
    @Override
    public void destroy() {
        for (String name : List.copyOf(allNames)) {
            destroy(name);
        }
    }

    /**
     * 一个被借出的池容器。
     *
     * @param name 容器名
     * @param key  所属镜像池
     */
    public record PooledContainer(String name, String key) {
    }
}
