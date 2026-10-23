package com.ojjudge.judge.impl;

import com.ojjudge.common.ErrorCode;
import com.ojjudge.exception.BusinessException;
import com.ojjudge.judge.CodeSandbox;
import com.ojjudge.judge.JudgeConstant;
import com.ojjudge.judge.LanguageEnum;
import com.ojjudge.judge.impl.ContainerPool.PooledContainer;
import com.ojjudge.judge.model.ExecuteRequest;
import com.ojjudge.judge.model.ExecuteResult;
import com.ojjudge.judge.model.JudgeStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 基于 Docker 容器的代码沙箱。
 *
 * <p>只负责一件事：给一段代码 + 一组输入，跑出每个输入对应的输出、耗时和内存。
 * 它不知道题目、不判定对错 —— 那是 JudgeService 的事。
 *
 * <h3>流程（容器只起 2 次，与用例数无关）</h3>
 * <ol>
 *   <li><b>编译</b>：起一次容器跑编译命令，stderr 落到 compile_error.txt</li>
 *   <li><b>运行</b>：再起一次容器，里面跑生成的 {@code run.sh}，
 *       <b>在一个容器内循环跑完所有用例</b>，每个用例写出
 *       {@code out_i.txt / err_i.txt / exit_i.txt / time_i.txt / mem_i.txt}</li>
 *   <li>宿主机读回这批文件，清理临时目录</li>
 * </ol>
 *
 * <p>之前是「一个用例一个容器」，10 个用例就是 11 次 {@code docker run}，
 * 每次几百毫秒全花在容器启停上。改成一个容器跑完后，启动开销从 N+1 次降到 2 次。
 *
 * <h3>耗时口径（重要）</h3>
 * <p>耗时由<b>容器内</b>用 {@code date} 前后打点算出（见 run.sh），
 * 只统计用户程序本身的运行时间（含 JVM 这类运行时启动开销），
 * <b>不含容器创建/销毁</b>。以前用宿主机 {@code System.currentTimeMillis()}
 * 包住整个 {@code docker run}，把容器启停的几百毫秒也算进成绩，
 * 于是会出现「用时 1450ms / 限制 1000ms」却判 Accepted 这种自相矛盾的展示。
 *
 * <h3>超时判定</h3>
 * <p>由容器内的看门狗负责：到点 {@code kill -9}，退出码 137 即超时。
 * 宿主机的 {@code waitFor} 只作为<b>兜底</b>（预算 = 用例数 × 单用例时限 + buffer），
 * 防止容器卡死时判题线程被永久占住 —— 它不再决定用户是否超时。
 *
 * <h3>内存统计</h3>
 * <p>容器内每 20ms 采样一次用户进程的 {@code /proc/<pid>/status} 的 VmHWM
 * （进程峰值 RSS，天然按进程隔离，容器复用也不会串），
 * 取所有用例的最大值。极短的程序可能一次都没采到，
 * 此时兜底读 cgroup 峰值（v2 的 {@code memory.peak} 或 v1 的
 * {@code memory.max_usage_in_bytes}）—— 只在一次性容器模式下可信，
 * 复用容器里它是累计值。
 *
 * <h3>安全边界（叠加，不依赖任何单一手段）</h3>
 * <ul>
 *   <li>{@code --network=none}  禁用网络，防外连 / 扫描内网</li>
 *   <li>{@code --memory}        限制内存，超限被 OOM kill（退出码 137）</li>
 *   <li>{@code --pids-limit}    限制进程数，防 fork 炸弹</li>
 *   <li>容器内看门狗        精确的每用例限时</li>
 *   <li>宿主机侧超时        {@code waitFor} + {@code destroyForcibly} + {@code docker rm -f}</li>
 *   <li>源码黑名单扫描    仅第一道廉价过滤，<b>不能当防线</b></li>
 * </ul>
 *
 * <p>⚠️ Windows 上要让 Docker 能挂载判题目录，需在 Docker Desktop 里
 * 开启对应盘符的 File sharing，否则 {@code docker run} 会挂不上卷。
 */
@Service
@Slf4j
public class DockerCodeSandbox implements CodeSandbox {

    /**
     * 容器内挂载点：宿主机的判题根目录挂到这里，每次判题用它的一个子目录。
     */
    static final String MOUNT_POINT = "/oj";

    /**
     * 工具链缓存的容器内挂载点。
     *
     * <p>Go 之类的编译器需要持久化的构建缓存，否则每个新容器都要重编一遍标准库
     * （实测冷 14 秒 / 热 4 秒）。把缓存目录一起挂载就能跨次复用。
     */
    static final String CACHE_MOUNT_POINT = "/ojcache";

    /**
     * 工具链缓存的宿主机目录名，挂在判题根目录下。
     */
    static final String CACHE_DIR_NAME = ".toolchain-cache";

    /**
     * 危险 API 黑名单。
     *
     * ⚠️ 这只是第一道廉价过滤，不是防线。字符串匹配很容易被绕过
     * （拼字符串、反射、Unicode 转义……）。真正的安全边界是容器。
     */
    private static final List<String> DANGEROUS_PATTERNS = List.of(
            // Java
            "Runtime.getRuntime",
            "ProcessBuilder",
            "System.exit",
            "java.io.File",
            "java.nio.file",
            "FileOutputStream",
            "FileInputStream",
            "sun.misc.Unsafe",
            // Python / 通用
            "os.system",
            "subprocess",
            "__import__",
            "shutil.rmtree"
    );

    /**
     * 宿主机侧的兜底宽限（毫秒），叠加在整个运行阶段预算之上。
     *
     * <p>它<b>不是</b>给用户代码的宽限 —— 用户代码的时限由容器内看门狗精确控制。
     * 这里只是留给容器启停、文件读写的余量，防止正常判题被宿主机误杀。
     */
    @Value("${judge.sandbox.timeout-buffer-ms:5000}")
    private long timeoutBufferMs;

    /**
     * 单用例硬超时上限（毫秒），防止题目配了荒谬时限长期占住判题线程。
     */
    @Value("${judge.sandbox.max-time-per-case-ms:10000}")
    private long maxTimePerCaseMs;

    /**
     * 容器内存硬上限（MB）。注意这是<b>运行</b>阶段的默认值，
     * 编译阶段另有一个更宽松的预算（见 {@code compile-memory-mb}）。
     */
    @Value("${judge.sandbox.max-memory-mb:256}")
    private long maxMemoryMb;

    /**
     * 编译阶段的内存上限（MB）。
     *
     * <p>为什么要比运行阶段大：编译器本身的开销和用户程序完全不是一个量级。
     * Go 冷启动时要连着编译一整张标准库依赖图，256MB 会很紧张。
     * 给编译单独一份预算，用户程序的限制该是多少还是多少。
     */
    @Value("${judge.sandbox.compile-memory-mb:512}")
    private long compileMemoryMb;

    /**
     * 容器可用的 CPU 核数上限。
     *
     * <p>限制核数有两个作用：一是防止一个多线程提交把宿主机所有核吃满，
     * 二是让耗时在不同机器上更可比（不然同一份代码在 4 核和 16 核机器上跑出来的
     * 时间可能差一大截）。
     *
     * <p>⚠️ 别以为加了它就能限制 Go 的构建并行度 —— Go 1.22 <b>不读</b> cgroup 的
     * CPU quota，{@code GOMAXPROCS} 依然会等于宿主机核数。
     * 构建并行度是在 {@link LanguageEnum#GO} 的编译命令里显式限制的。
     */
    @Value("${judge.sandbox.max-cpus:1}")
    private double maxCpus;

    /**
     * 容器内进程 + 线程总数上限。
     *
     * <p>这个值数的是 <b>cgroup pids.max 的 task 数，也就是线程也算</b>，不是进程数。
     * 一开始设成 64，结果 Go 完全跑不了：构建会按 host 核数并发 fork 十几个
     * {@code compile}，每个还带若干线程，轻轻松松顶破配额，之后所有 fork 都返回
     * EAGAIN（{@code resource temporarily unavailable}）。
     * 详细的排查过程见 SANDBOX-TUTORIAL.md 第 7 节。
     *
     * <p>放宽到 512 仍然能挡住 fork 炸弹 —— 那种东西是指数级翻倍的，
     * 几毫秒内就会撞到上限。
     */
    @Value("${judge.sandbox.pids-limit:512}")
    private long pidsLimit;

    /**
     * 编译阶段的超时（毫秒）。编译不该受题目时限约束，单独给一个宽松的值。
     */
    @Value("${judge.sandbox.compile-timeout-ms:30000}")
    private long compileTimeoutMs;

    /**
     * 判题临时目录的根。留空则用系统临时目录
     * （Windows 上通常是 {@code C:\Users\...\AppData\Local\Temp}）。
     *
     * <p>想避开 C 盘就填 E 盘路径，比如 {@code E:/oj-judge-tmp}。
     * 这样只需要把 E 盘加进 Docker Desktop 的 File sharing，C 盘不用共享。
     *
     * <p>注意：这里只放<strong>源码和用例输入输出</strong>（几 KB 的临时文件），
     * 判完即删。真正吃磁盘的是 Docker 的镜像和容器本体 —— 那要在
     * Docker Desktop → Settings → Resources → Advanced → Disk image location 里改。
     *
     * <p>容器池会把这个根目录整体挂进常驻容器，所以路径必须是<b>稳定</b>的。
     */
    @Value("${judge.sandbox.work-dir:}")
    private String workDirRoot;

    private final ContainerPool pool;

    public DockerCodeSandbox(ContainerPool pool) {
        this.pool = pool;
    }

    @Override
    public ExecuteResult execute(ExecuteRequest request) {
        LanguageEnum language = LanguageEnum.of(request.getLanguage());
        if (language == null) {
            return ExecuteResult.fail(JudgeStatus.COMPILE_ERROR,
                "不支持的编程语言：" + request.getLanguage());
        }
        if (containsDangerousCode(request.getCode())) {
            return ExecuteResult.fail(JudgeStatus.DANGEROUS, "代码包含危险操作");
        }

        List<String> inputs = request.getInputs() == null ? List.of() : request.getInputs();
        if (inputs.isEmpty()) {
            // 没有用例不能算通过，否则"空题目"会被误判成 AC
            return ExecuteResult.fail(JudgeStatus.COMPILE_ERROR, "这道题还没有配置判题用例");
        }

        String dirName = "oj-" + UUID.randomUUID().toString().substring(0, 8);
        Path judgeDir = null;
        // 编译和运行复用同一个池容器：借一次、还一次，中间过程共享同一个工作目录
        PooledContainer pooled = pool.borrow(language);
        try {
            judgeDir = createJudgeDir(dirName);
            Files.writeString(judgeDir.resolve(language.getFileName()),
                    nullToEmpty(request.getCode()), StandardCharsets.UTF_8);
            for (int i = 0; i < inputs.size(); i++) {
                Files.writeString(judgeDir.resolve("in_" + i + ".txt"),
                        nullToEmpty(inputs.get(i)), StandardCharsets.UTF_8);
            }
            Files.writeString(judgeDir.resolve("run.sh"),
                    buildRunScript(inputs.size(), perCaseMs(request.getTimeLimit()), language.getRunCommand()),
                    StandardCharsets.UTF_8);

            String memory = memoryLimit(request.getMemoryLimit());
            long memoryBytes = memoryLimitBytes(request.getMemoryLimit());
            // 编译和运行用不同的口径：编译器开销远大于用户程序，
            // 拿运行阶段的内存预算去限制编译器，Go 这种要连编标准库的会先把自己饿死
            String compileMemory = compileMemoryMb + "m";

            // 1) 编译
            Outcome compile = exec(language, pooled, dirName,
                    language.getCompileCommand() + " 2> compile_error.txt",
                    compileTimeoutMs, compileMemory);
            if (compile.timedOut()) {
                return ExecuteResult.fail(JudgeStatus.TIME_LIMIT_EXCEEDED, "编译超时");
            }
            // Docker 连不上 ≠ 用户代码有问题。必须先排除，否则会误报成"编译错误"
            if (isDaemonUnavailable(compile.stderr())) {
                return ExecuteResult.fail(JudgeStatus.SYSTEM_ERROR,
                        "判题服务不可用：Docker 守护进程未启动或无法连接。请启动 Docker Desktop 后重试。"
                                + "\n原始错误：" + compile.stderr());
            }
            if (isContainerGone(compile.stderr())) {
                // 池容器在判题过程中没了（被手动删 / Docker 重启过），
                // 销毁它让下次重建，并如实报错 —— 不要伪装成编译错误
                pool.discard(pooled);
                return ExecuteResult.fail(JudgeStatus.SYSTEM_ERROR,
                        "判题容器异常退出，请重试。\n原始错误：" + compile.stderr());
            }
            if (compile.exitCode() != 0) {
                return ExecuteResult.fail(JudgeStatus.COMPILE_ERROR,
                        readIfExists(judgeDir.resolve("compile_error.txt")) + compile.stderr());
            }

            // 2) 运行：一个容器跑完所有用例
            long perCase = perCaseMs(request.getTimeLimit());
            long runBudget = perCase * (long) inputs.size() + timeoutBufferMs;
            Outcome run = exec(language, pooled, dirName, "sh run.sh", runBudget, memory);

            if (isDaemonUnavailable(run.stderr())) {
                return ExecuteResult.fail(JudgeStatus.SYSTEM_ERROR,
                        "判题服务不可用：Docker 守护进程未启动或无法连接。\n原始错误：" + run.stderr());
            }
            if (isContainerGone(run.stderr())) {
                pool.discard(pooled);
                return ExecuteResult.fail(JudgeStatus.SYSTEM_ERROR,
                        "判题容器异常退出，请重试。\n原始错误：" + run.stderr());
            }

            // 3) 读回每个用例的产物
            List<String> outputs = new ArrayList<>();
            List<Long> times = new ArrayList<>();
            long peakMemory = 0;
            for (int i = 0; i < inputs.size(); i++) {
                String exitFile = readIfExists(judgeDir.resolve("exit_" + i + ".txt"));
                if (exitFile.isBlank()) {
                    // 连退出码都没写出来：容器被兜底预算杀掉了，确实太慢
                    return ExecuteResult.fail(run.timedOut()
                            ? JudgeStatus.TIME_LIMIT_EXCEEDED : JudgeStatus.SYSTEM_ERROR,
                            run.timedOut() ? "运行超时" : "判题容器未产出结果，请重试");
                }
                int exit = (int) parseLong(exitFile, -1);
                long elapsed = parseLong(readIfExists(judgeDir.resolve("time_" + i + ".txt")), 0);
                long mem = parseLong(readIfExists(judgeDir.resolve("mem_" + i + ".txt")), 0);
                peakMemory = Math.max(peakMemory, mem);
                times.add(elapsed);
                outputs.add(readIfExists(judgeDir.resolve("out_" + i + ".txt")));

                if (exit != 0) {
                    String err = readIfExists(judgeDir.resolve("err_" + i + ".txt"));
                    // 137 = 128 + SIGKILL，看门狗超时和 OOM 都是它。
                    // 用耗时区分：贴着时限被杀是超时，远早于时限就是内存爆了
                    if (exit == 137 && elapsed < perCase * 0.9) {
                        return ExecuteResult.fail(JudgeStatus.MEMORY_LIMIT_EXCEEDED,
                                "内存超出限制" + (err.isBlank() ? "" : "\n" + truncate(err, 500)));
                    }
                    if (exit == 137) {
                        return ExecuteResult.fail(JudgeStatus.TIME_LIMIT_EXCEEDED,
                                "运行超时（单个用例上限 " + perCase + " ms）");
                    }
                    return ExecuteResult.fail(JudgeStatus.RUNTIME_ERROR, truncate(err, 1000));
                }
            }

            // 采样一次都没命中（程序太短）时，用 cgroup 峰值兜底。
            // 复用容器里这个值是累计的，不能信，所以只在一次性容器模式下用。
            if (peakMemory <= 0 && pooled == null) {
                peakMemory = parseLong(readIfExists(judgeDir.resolve("cg_mem.txt")), 0);
            }
            // 内存超限但程序正常退出：容器没 OOM 不代表没超题目限制
            if (peakMemory > 0 && memoryBytes > 0 && peakMemory > memoryBytes) {
                return failed(JudgeStatus.MEMORY_LIMIT_EXCEEDED,
                        "内存超出限制（峰值 " + (peakMemory / 1024 / 1024) + " MB，上限 "
                                + (memoryBytes / 1024 / 1024) + " MB）",
                        outputs, times, peakMemory);
            }

            return ExecuteResult.success(outputs, times, peakMemory > 0 ? peakMemory : null, 0);
        } catch (IOException e) {
            log.error("沙箱执行失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "判题服务异常");
        } catch (InterruptedException e) {
            // 被中断时要把中断状态补回去，否则上层（线程池 / 容器）感知不到
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "判题被中断");
        } finally {
            if (judgeDir != null) {
                // 复用容器时先在容器里删（容器里是 root，权限一定够），
                // 再从宿主机删一遍兜底
                if (pooled != null) {
                    removeInContainer(pooled.name(), MOUNT_POINT + "/" + dirName);
                }
                deleteDirectory(judgeDir);
            }
            pool.release(pooled);
        }
    }

    // ------------------------------------------------------------------ 脚本

    /**
     * 生成容器内批量跑用例的脚本。
     *
     * <p>为什么不直接在 Java 里循环 {@code docker run}：那样容器启停开销会乘以用例数。
     * 把循环放进容器，启动成本就只付一次。
     *
     * <p>为什么用看门狗而不是 {@code timeout} 命令：三个镜像的 {@code timeout}
     * 语法不一致 —— {@code golang:1.22-alpine} 是 busybox（只支持整数秒 {@code -t}），
     * 另外两个是 GNU coreutils（支持小数秒）。用 shell 自己实现，
     * 三个镜像行为完全一致，还能顺便把内存采样做了。
     *
     * @param caseCount   用例数
     * @param perCaseMs   单用例时限（毫秒）
     * @param runCommand  语言对应的运行命令，使用相对路径（如 {@code java -cp . Main}）
     */
    private String buildRunScript(int caseCount, long perCaseMs, String runCommand) {
        return """
                #!/bin/sh
                # 判题运行脚本：在单个容器内依次跑完所有用例。
                # 每个用例：后台启动用户程序 → 后台看门狗到点 SIGKILL → 采样内存 → 记耗时与退出码。
                COUNT=__COUNT__

                # busybox 的 date 可能不支持 %N（纳秒），退化到秒级 —— 精度差但不会算出垃圾值
                case "$(date +%s%N 2>/dev/null)" in
                  ''|*[!0-9]*) NS=0 ;;
                  *) NS=1 ;;
                esac

                # 采样间隔；不支持小数 sleep 的 busybox 退化成 1 秒，
                # 此时看门狗也只能按整秒计时（向上取整，宁可宽松也不误杀）
                if sleep 0.05 2>/dev/null; then
                  TICK=0.05
                  LIMIT_S=__LIMIT_S__
                else
                  TICK=1
                  LIMIT_S=__LIMIT_S_CEIL__
                fi

                now_ms() {
                  if [ "$NS" = "1" ]; then
                    echo $(( $(date +%s%N) / 1000000 ))
                  else
                    echo $(( $(date +%s) * 1000 ))
                  fi
                }

                i=0
                while [ "$i" -lt "$COUNT" ]; do
                  start=$(now_ms)
                  __RUN_CMD__ < "in_$i.txt" > "out_$i.txt" 2> "err_$i.txt" &
                  pid=$!
                  # 看门狗：到点直接 SIGKILL。
                  # 为什么不在下面那个轮询里反复调 date 判超时：每次 date 都是一次
                  # fork/exec，塞在 50ms 一轮的循环里会抢用户程序的 CPU，
                  # 把本来一两百毫秒的程序拖成几百毫秒 —— 反而更不准。
                  ( sleep "$LIMIT_S"; kill -9 "$pid" 2>/dev/null ) &
                  wd=$!
                  peak=0
                  while kill -0 "$pid" 2>/dev/null; do
                    v=$(awk '/VmHWM/{print $2}' "/proc/$pid/status" 2>/dev/null)
                    if [ -n "$v" ] && [ "$v" -gt "$peak" ] 2>/dev/null; then peak=$v; fi
                    sleep "$TICK"
                  done
                  wait "$pid" 2>/dev/null
                  rc=$?
                  kill "$wd" 2>/dev/null
                  echo "$rc" > "exit_$i.txt"
                  echo $(( $(now_ms) - start )) > "time_$i.txt"
                  echo $(( peak * 1024 )) > "mem_$i.txt"
                  i=$((i + 1))
                done

                # cgroup 峰值兜底：程序可能在采样间隔内就跑完了，VmHWM 一次都没采到
                cg=0
                if [ -f /sys/fs/cgroup/memory.peak ]; then
                  cg=$(cat /sys/fs/cgroup/memory.peak 2>/dev/null)
                elif [ -f /sys/fs/cgroup/memory/memory.max_usage_in_bytes ]; then
                  cg=$(cat /sys/fs/cgroup/memory/memory.max_usage_in_bytes 2>/dev/null)
                fi
                echo "${cg:-0}" > cg_mem.txt
                """
                .replace("__COUNT__", String.valueOf(caseCount))
                .replace("__LIMIT_S__", secondsLiteral(perCaseMs))
                .replace("__LIMIT_S_CEIL__", String.valueOf((perCaseMs + 999) / 1000))
                .replace("__RUN_CMD__", runCommand);
    }

    /**
     * 毫秒 → {@code sleep} 认得的秒数字面量。
     *
     * <pre>
     *   1000 -> "1"     1500 -> "1.5"     2000 -> "2"
     * </pre>
     */
    private static String secondsLiteral(long ms) {
        if (ms % 1000 == 0) {
            return String.valueOf(ms / 1000);
        }
        return String.format(Locale.ROOT, "%.3f", ms / 1000.0)
                .replaceAll("0+$", "");
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 在容器里执行一条命令：优先用借来的池容器（{@code docker exec}），
     * 否则起一次性容器（{@code docker run --rm}）。
     *
     * <p>命令以 {@code sh -c} 形式传进去，工作目录是本次判题的子目录，
     * 所以脚本里可以直接用相对路径。
     */
    private Outcome exec(LanguageEnum language, PooledContainer pooled, String dirName,
                         String command, long budgetMs, String memory)
            throws IOException, InterruptedException {
        String workDir = MOUNT_POINT + "/" + dirName;
        if (pooled != null) {
            return dockerExec(pooled.name(), workDir, command, budgetMs);
        }
        return dockerRun(language, workDir, command, budgetMs, memory);
    }

    /**
     * 一次性容器：跑完即销毁（{@code --rm}）。
     *
     * <p>参数用 List 传给 ProcessBuilder，<b>不做字符串拼接</b> ——
     * 这样宿主机 shell 不会把命令里的引号、分号当成语法解释。
     */
    private Outcome dockerRun(LanguageEnum language, String workDir, String command,
                              long budgetMs, String memory)
            throws IOException, InterruptedException {
        String containerName = "oj-judge-" + UUID.randomUUID().toString().substring(0, 8);
        Process process = new ProcessBuilder("docker", "run", "--rm",
                        "--network=none",
                        "--memory=" + memory,
                        "--memory-swap=" + memory,
                        "--pids-limit=" + pidsLimit,
                        "--cpus=" + maxCpus,
                        "--name", containerName,
                        "-v", mountArg(resolveRoot(), MOUNT_POINT),
                        "-v", mountArg(resolveCacheDir(), CACHE_MOUNT_POINT),
                        "-w", workDir,
                        "-e", "HOME=/tmp",
                        language.getImage(),
                        "sh", "-c", command)
                .start();

        // 没有 stdin 要喂（用例输入是文件），直接关掉，避免某些镜像一直等输入
        try (OutputStream os = process.getOutputStream()) {
            os.close();
        }

        boolean finished = process.waitFor(budgetMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            // destroyForcibly 只能杀掉 docker CLI 这一层，容器未必跟着退出，
            // 所以还要 docker rm -f 兜底
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            forceRemove(containerName);
            return Outcome.timeout();
        }
        return Outcome.done(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    /**
     * 在常驻容器里执行（{@code docker exec}），省掉容器创建/销毁的开销。
     *
     * <p>注意 {@code docker exec} 没法再设 {@code --memory} 等参数，
     * 那些限制在池容器创建时就定好了。
     */
    private Outcome dockerExec(String containerName, String workDir, String command, long budgetMs)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "exec",
                        "-w", workDir,
                        containerName,
                        "sh", "-c", command)
                .start();

        try (OutputStream os = process.getOutputStream()) {
            os.close();
        }

        boolean finished = process.waitFor(budgetMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return Outcome.timeout();
        }
        return Outcome.done(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 目录

    /**
     * 建一个本次判题专用的子目录（在判题根目录下）。
     */
    private Path createJudgeDir(String dirName) throws IOException {
        return Files.createDirectories(resolveRoot().resolve(dirName));
    }

    /**
     * 判题根目录。配了 {@code judge.sandbox.work-dir} 就用它，
     * 否则用系统临时目录下的 {@code oj-judge} 子目录。
     *
     * <p>容器池在创建时把这个目录整体挂进容器，所以每次判题只需建子目录，
     * 不需要重新挂载。
     *
     * 为什么不能直接用系统临时目录当根：挂进去的东西用户代码全都看得见、
     * 也写得动。用临时目录本身当根等于把用户 Temp 下的其它文件一并暴露给提交的代码，
     * 而那本来是不该被看到的。专门开一层 {@code oj-judge} 子目录，
     * 暴露面就只剩下判题自己的文件。
     */
    private Path resolveRoot() throws IOException {
        Path root = StringUtils.hasText(workDirRoot)
                ? Paths.get(workDirRoot.trim())
                : Paths.get(System.getProperty("java.io.tmpdir"), "oj-judge");
        Files.createDirectories(root);
        return root;
    }

    /**
     * 工具链缓存目录（宿主机侧）。放在判题根目录下，跟着根目录一起已经在
     * Docker Desktop 的 File sharing 里，不需要再额外共享盘符。
     */
    private Path resolveCacheDir() throws IOException {
        Path cache = resolveRoot().resolve(CACHE_DIR_NAME);
        if (!Files.exists(cache)) {
            Files.createDirectories(cache);
        }
        return cache;
    }

    /**
     * 把宿主机路径转成 {@code docker -v} 认得的形式。
     *
     * <p>Windows 上是 {@code C:\...} 或 {@code E:\...}。Docker Desktop 虽然也接受
     * {@code C:\...}，但统一转成 POSIX 风格 {@code /c/...} 更稳
     * （反斜杠在各种 shell / 工具链里容易被吃掉一层转义）。
     *
     * <pre>
     *   E:\oj-judge-tmp  ->  /e/oj-judge-tmp:/oj
     *   /tmp             ->  /tmp:/oj              （Linux 原样）
     * </pre>
     */
    private String mountArg(Path hostPath, String target) {
        String path = hostPath.toString().replace('\\', '/');
        if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
            return "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2) + ":" + target;
        }
        return path + ":" + target;
    }

    /**
     * 在容器里删掉本次判题目录。
     *
     * <p>容器里是 root，删除一定成功；宿主机侧再删一遍作为兜底。
     * 复用容器必须清干净，否则上一次判题的残留会留给下一次。
     */
    private void removeInContainer(String containerName, String path) {
        try {
            Process process = new ProcessBuilder("docker", "exec", containerName, "rm", "-rf", path)
                    .redirectErrorStream(true)
                    .start();
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("清理容器内判题目录失败：{}", path);
        }
    }

    /**
     * 递归删除临时目录
     */
    private void deleteDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("删除临时文件失败：{}", path);
                }
            });
        } catch (IOException e) {
            log.warn("清理临时目录失败：{}", dir);
        }
    }

    // ------------------------------------------------------------------ 判定辅助

    /**
     * stderr 里出现这些字样，说明根本不是用户代码的问题，
     * 而是 <strong>Docker 守护进程本身不可用</strong>（Docker Desktop 没启动、被退出、正在重启）。
     *
     * <p>为什么必须识别：不识别的话，这种情况会被当成"编译失败"返回
     * {@code 编译错误} —— 用户看到的是"你的代码编译不过"，
     * 于是反复检查自己的代码，而真实原因是环境没起来。误导性极强。
     */
    private static final List<String> DAEMON_UNAVAILABLE_MARKERS = List.of(
            "Cannot connect to the Docker daemon",
            "failed to connect to the docker API",
            "dockerDesktopLinuxEngine",
            "docker_engine",
            "Is the docker daemon running",
            "error during connect"
    );

    /**
     * 池容器在判题过程中消失（被手动删除、Docker 重启）时 docker 报的错。
     */
    private static final List<String> CONTAINER_GONE_MARKERS = List.of(
            "No such container",
            "is not running",
            "container not found"
    );

    private boolean isDaemonUnavailable(String stderr) {
        return matches(stderr, DAEMON_UNAVAILABLE_MARKERS);
    }

    private boolean isContainerGone(String stderr) {
        return matches(stderr, CONTAINER_GONE_MARKERS);
    }

    private boolean matches(String stderr, List<String> markers) {
        if (stderr == null || stderr.isBlank()) {
            return false;
        }
        return markers.stream().anyMatch(stderr::contains);
    }

    /**
     * 单用例时限（毫秒）：取题目配置，夹在 [1s, 硬上限] 之间。
     *
     * <p>下限 1s 是刻意的：耗时口径把 JVM 启动（约 150ms）算进用户时间，
     * 若允许题目配成 300ms 这类值，Java 提交将永远不可能通过。
     * 宁可放宽，也不要产生"怎么都过不了"的题目。
     */
    private long perCaseMs(Long timeLimit) {
        if (timeLimit == null || timeLimit <= 0) {
            return JudgeConstant.DEFAULT_TIME_LIMIT;
        }
        return Math.min(Math.max(timeLimit, 1000L), maxTimePerCaseMs);
    }

    /**
     * 容器内存上限：题目配置（字节）换算成 MB，再夹在 [16MB, 硬上限] 之间。
     */
    private String memoryLimit(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return maxMemoryMb + "m";
        }
        long mb = (long) Math.ceil(bytes / 1024.0 / 1024.0);
        return Math.max(16, Math.min(mb, maxMemoryMb)) + "m";
    }

    /**
     * 题目配置的内存上限（字节），用于实测峰值超限判定。
     */
    private long memoryLimitBytes(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return JudgeConstant.DEFAULT_MEMORY_LIMIT;
        }
        return Math.min(bytes, maxMemoryMb * 1024 * 1024L);
    }

    /**
     * 源码里是否命中危险 API 黑名单
     */
    private boolean containsDangerousCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return DANGEROUS_PATTERNS.stream().anyMatch(code::contains);
    }

    /**
     * 兜底删容器：超时强杀时容器不一定跟着退出，必须显式清理，否则会残留。
     */
    private void forceRemove(String containerName) {
        try {
            Process process = new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .start();
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("清理容器失败：{}", containerName);
        }
    }

    private String readIfExists(Path file) {
        try {
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("读取沙箱输出失败：{}", file.getFileName());
        }
        return "";
    }

    private long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 截断过长的错误输出。
     */
    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...（已截断）";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 带上已经跑出来的部分数据再判失败，
     * 这样前端仍能看到耗时/内存，而不是一片空白。
     */
    private static ExecuteResult failed(JudgeStatus status, String message,
                                        List<String> outputs, List<Long> times, Long memory) {
        ExecuteResult result = ExecuteResult.success(outputs, times, memory, null);
        result.setStatus(status);
        result.setErrorMessage(message);
        return result;
    }

    /**
     * 容器执行结果。
     *
     * @param exitCode  退出码，null 表示超时被杀
     * @param stdout   容器内程序打印到 stdout 的内容
     * @param stderr   容器 stderr（docker 自身报错也在这里）
     * @param timedOut 是否超时
     */
    private record Outcome(Integer exitCode, String stdout, String stderr, boolean timedOut) {

        static Outcome timeout() {
            return new Outcome(null, "", "", true);
        }

        static Outcome done(Integer exitCode, String stdout, String stderr) {
            return new Outcome(exitCode, stdout, stderr, false);
        }
    }
}
