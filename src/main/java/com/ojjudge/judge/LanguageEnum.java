package com.ojjudge.judge;

import lombok.Getter;

import java.util.Arrays;

/**
 * 支持的编程语言。
 *
 * <p>每种语言声明五件事：源码文件名、容器镜像、编译命令、运行命令、预热用的片段。
 * 加一门新语言只需要在这里加一个枚举值，判题流程不用动。
 *
 * <p><b>编译/运行命令一律用相对路径</b>（{@code .} 和 {@code ./main}），
 * 不能写 {@code /app} 这类绝对路径 —— 容器内的挂载点由沙箱决定，
 * 语言枚举不应该知道它。执行时的工作目录由 {@code -w} 指定为本次判题的临时目录。
 */
@Getter
public enum LanguageEnum {


    JAVA("java", "Main.java", "eclipse-temurin:21-jdk",
            "javac -encoding UTF-8 -d . Main.java", "java -cp . Main",
            """
            public class Main {
                public static void main(String[] args) {
                }
            }
            """),

    /**
     * C++
     */
    CPP("cpp", "main.cpp", "gcc:13",
            "g++ -o main main.cpp", "./main",
            """
            int main() {
                return 0;
            }
            """),

    /**
     * Go。用 alpine 镜像，编译产物是静态二进制
     *
     * <p><b>编译命令前面那一串环境变量不是装饰，别删</b> ——
     * 每一条都是为了治一个真实的失败：
     *
     * <ul>
     *   <li>{@code GOMAXPROCS=2}：<b>最关键的一条。</b>
     *       Go 构建的并行度默认跟着宿主机核数走，实测在 16 核机器上会同时 fork 出
     *       十几个 {@code compile} 子进程。而 cgroup 的 {@code pids.max} 数的
     *       是<b>线程</b>不是进程，并发编译轻松顶破容器配额，
     *       之后每次 fork 都返回 {@code resource temporarily unavailable (EAGAIN)}，
     *       现象就是「Go 代码怎么提交都过不了」。
     *       <br>⚠️ 光加 {@code --cpus=1} 是<b>没用</b>的：Go 1.22 不读 cgroup 的
     *       CPU quota，实测加了 quota 之后 {@code go env GOMAXPROCS} 仍然是宿主机核数。</li>
     *   <li>{@code GOFLAGS=-buildvcs=false}：跳过 go build 为了打版本戳去探 VCS
     *       （少一次 git 子进程），而且容器无网，探测只会白等。</li>
     *   <li>{@code GOCACHE / GOPATH}：指到沙箱挂载的<b>持久化</b>工具链缓存。
     *       不指定的话每个新容器都是冷缓存，要重编一遍标准库 ——
     *       实测冷编译 14 秒、热编译 4 秒，冷编译的 fork 次数也多得多。</li>
     * </ul>
     */
    GO("go", "main.go", "golang:1.22-alpine",
            "GOMAXPROCS=2 GOFLAGS=-buildvcs=false"
                    + " GOCACHE=/ojcache/go-build GOPATH=/ojcache/gopath"
                    + " go build -o main main.go",
            "./main",
            """
            package main

            func main() {
            }
            """),
    ;

    private final String value;

    private final String fileName;

    private final String image;

    private final String compileCommand;

    private final String runCommand;

    /**
     * 预热用的一小段源码，编译并运行一遍把容器的工具链缓存填热。
     *
     * <p>刻意什么都不输出 —— 预热只需要 toolchain 跑通，不验证结果正确性。
     */
    private final String warmUpSource;

    LanguageEnum(String value, String fileName, String image,
                 String compileCommand, String runCommand, String warmUpSource) {
        this.value = value;
        this.fileName = fileName;
        this.image = image;
        this.compileCommand = compileCommand;
        this.runCommand = runCommand;
        this.warmUpSource = warmUpSource;
    }

    public static LanguageEnum of(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.value.equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElse(null);
    }
}
