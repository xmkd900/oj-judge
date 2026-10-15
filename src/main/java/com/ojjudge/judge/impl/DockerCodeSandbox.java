package com.ojjudge.judge.impl;

import com.ojjudge.common.ErrorCode;
import com.ojjudge.exception.BusinessException;
import com.ojjudge.judge.CodeSandbox;
import com.ojjudge.judge.JudgeConstant;
import com.ojjudge.judge.LanguageEnum;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 基于 Docker 容器的代码沙箱。
 *
 * <p>只负责一件事：给一段代码 + 一组输入，跑出每个输入对应的输出和耗时。
 * 它不知道题目、不判定对错 —— 那是 JudgeService 的事。
 *
 * <p><b>流程</b>：建临时目录 → 写源码 → 起容器编译 → 逐用例起容器运行 → 读输出 → 清理
 *
 * <p><b>为什么编译一次、运行多次分开起容器</b>：
 * 每个阶段都能独立超时、独立回收，不会因为一个用例卡死而拖垮整轮判题。
 * 代价是多次容器启动开销（每次几百毫秒），个人项目可以接受。
 *
 * <h3>安全边界（叠加，不依赖任何单一手段）</h3>
 * <ul>
 *   <li>{@code --rm}            跑完即销毁，不留残留容器</li>
 *   <li>{@code --network=none}  禁用网络，防外连 / 扫描内网</li>
 *   <li>{@code --memory}        限制内存，超限被 OOM kill（退出码 137）</li>
 *   <li>{@code --pids-limit}    限制进程数，防 fork 炸弹</li>
 *   <li>宿主机侧超时        {@code waitFor} + {@code destroyForcibly} + {@code docker rm -f}</li>
 *   <li>源码黑名单扫描    仅第一道廉价过滤，<b>不能当防线</b></li>
 * </ul>
 *
 * <p>⚠️ Windows 上要让 Docker 能挂载临时目录，需在 Docker Desktop 里
 * 开启对应盘符的 File sharing，否则 {@code docker run} 会挂不上卷。
 */
@Service
@Slf4j
public class DockerCodeSandbox implements CodeSandbox {

    /**
     * 容器内的工作目录
     */
    private static final String WORK_DIR = "/app";

    /**
     * 危险 API 黑名单。
     *
     * ⚠️ 这只是第一道廉价过滤，不是防线。字符串匹配很容易被绕过
     * （拼字符串、反射、Unicode 转义……）。真正的安全边界是容器。
     */
    private static final List<String> DANGEROUS_PATTERNS = List.of(
            "Runtime.getRuntime",
            "ProcessBuilder",
            "System.exit",
            "java.io.File",
            "java.nio.file",
            "FileOutputStream",
            "FileInputStream",
            "sun.misc.Unsafe"
    );

    /**
     * 宿主机侧在题目时限之外额外给的宽限（毫秒）。
     */
    @Value("${judge.sandbox.timeout-buffer-ms:5000}")
    private long timeoutBufferMs;

    /**
     * 单用例硬超时上限（毫秒），防止题目配了荒谬时限长期占住判题线程。
     */
    @Value("${judge.sandbox.max-time-per-case-ms:10000}")
    private long maxTimePerCaseMs;

    /**
     * 容器内存硬上限（MB）。
     */
    @Value("${judge.sandbox.max-memory-mb:256}")
    private long maxMemoryMb;

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
     */
    @Value("${judge.sandbox.work-dir:}")
    private String workDirRoot;

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

        Path workDir = null;
        String containerName = "oj-judge-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            workDir = createWorkDir();
            Files.writeString(workDir.resolve(language.getFileName()),
                    nullToEmpty(request.getCode()), StandardCharsets.UTF_8);

            long perCaseMs = perCaseMs(request.getTimeLimit());
            long budgetMs = perCaseMs + timeoutBufferMs;
            String memory = memoryLimit(request.getMemoryLimit());

            // 1) 编译：stderr 重定向到文件，失败时才拿得到报错
            Outcome compile = dockerRun(language, workDir, containerName + "-c",
                    language.getCompileCommand() + " 2> " + WORK_DIR + "/compile_error.txt",
                    null, budgetMs, memory);
            if (compile.timedOut()) {
                return ExecuteResult.fail(JudgeStatus.TIME_LIMIT_EXCEEDED, "编译超时");
            }
            // Docker 连不上 ≠ 用户代码有问题。必须先排除，否则会误报成"编译错误"
            if (isDaemonUnavailable(compile.stderr())) {
                return ExecuteResult.fail(JudgeStatus.SYSTEM_ERROR,
                        "判题服务不可用：Docker 守护进程未启动或无法连接。请启动 Docker Desktop 后重试。"
                                + "\n原始错误：" + compile.stderr());
            }
            if (compile.exitCode() != 0) {
                return ExecuteResult.fail(JudgeStatus.COMPILE_ERROR,
                        compile.stderr() + readIfExists(workDir.resolve("compile_error.txt")));
            }

            // 2) 逐用例运行
            List<String> outputs = new ArrayList<>();
            List<Long> times = new ArrayList<>();
            for (String input : inputs) {
                long start = System.currentTimeMillis();
                Outcome run = dockerRun(language, workDir, containerName + "-r",
                        language.getRunCommand(), input, budgetMs, memory);
                times.add(System.currentTimeMillis() - start);

                if (run.timedOut()) {
                    return ExecuteResult.fail(JudgeStatus.TIME_LIMIT_EXCEEDED, "运行超时");
                }
                if (isDaemonUnavailable(run.stderr())) {
                    return ExecuteResult.fail(JudgeStatus.SYSTEM_ERROR,
                            "判题服务不可用：Docker 守护进程未启动或无法连接。"
                                    + "\n原始错误：" + run.stderr());
                }
                outputs.add(run.stdout());
                if (run.exitCode() != 0) {
                    return ExecuteResult.fail(JudgeStatus.RUNTIME_ERROR, run.stderr());
            }
        }

            return ExecuteResult.success(outputs, times, 0);
        } catch (IOException e) {
            log.error("沙箱执行失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "判题服务异常");
        } catch (InterruptedException e) {
            // 被中断时要把中断状态补回去，否则上层（线程池 / 容器）感知不到
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "判题被中断");
        } finally {
            forceRemove(containerName + "-c");
            forceRemove(containerName + "-r");
            deleteDirectory(workDir);
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 启动容器执行一条命令，并等待结束。
     *
     * <p>命令以 List 形式传给 ProcessBuilder，<b>不做字符串拼接</b> ——
     * 这样宿主机 shell 不会把用户代码里的引号、分号当成语法解释。
     * 输入通过 stdin 喂进去，不落盘再挂载，Windows 上更稳。
     */
    private Outcome dockerRun(LanguageEnum language, Path workDir, String containerName,
                     String command, String stdin, long budgetMs, String memory)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "run", "--rm", "-i",
                        "--network=none",
                        "--memory=" + memory,
                        "--memory-swap=" + memory,
                        "--pids-limit=64",
                        "--name", containerName,
                        "-v", mountArg(workDir),
                        "-w", WORK_DIR,
                        language.getImage(),
                        "sh", "-c", command)
                .start();

        try (OutputStream os = process.getOutputStream()) {
            if (stdin != null) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }

        boolean finished = process.waitFor(budgetMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            // destroyForcibly 只能杀掉 docker CLI 这一层，容器未必跟着退出，
            // 所以 finally 里还要 docker rm -f 兜底
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return Outcome.timeout();
        }
        return Outcome.done(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    /**
     * 建一个本次判题专用的临时目录。
     *
     * <p>配了 {@code judge.sandbox.work-dir} 就建在那下面（比如 E 盘），
     * 否则退回系统临时目录。目录不存在会自动建。
     */
    private Path createWorkDir() throws IOException {
        if (StringUtils.hasText(workDirRoot)) {
            Path root = Paths.get(workDirRoot.trim());
            Files.createDirectories(root);
            return Files.createTempDirectory(root, "oj-");
        }
        return Files.createTempDirectory("oj-");
    }

    /**
     * 把宿主机临时目录转成 {@code docker -v} 认得的形式。
     *
     * <p>Windows 上临时目录是 {@code C:\Users\...\Temp\oj-xxx}。
 * Docker Desktop 虽然也接受 {@code C:\...}，但统一转成 POSIX 风格
 * {@code /c/Users/...} 更稳（反斜杠在各种 shell / 工具链里容易被吃掉一层转义）。
 *
 * <pre>
 *   C:\Users\46684\AppData\Local\Temp\oj-123  ->  /c/Users/46684/AppData/Local/Temp/oj-123
 *   /tmp/oj-123                               ->  /tmp/oj-123       （Linux 原样）
 * </pre>
 *
 * <p>这样同一份代码在 Windows（Docker Desktop）和 Linux 上都能直接用。
     */
    private String mountArg(Path workDir) {
        String path = workDir.toString().replace('\\', '/');
        if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
            return "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2) + ":" + WORK_DIR;
        }
        return path + ":" + WORK_DIR;
    }

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
     * stderr 是否表明 Docker 守护进程不可用。
     */
    private boolean isDaemonUnavailable(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return false;
        }
        return DAEMON_UNAVAILABLE_MARKERS.stream().anyMatch(stderr::contains);
    }

    /**
     * 单用例时限（毫秒）：取题目配置，夹在 [1s, 硬上限] 之间。
     * 下限 1s 是为了避免误配成 0 导致瞬间超时。
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
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
