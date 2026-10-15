package com.ojjudge.judge;

import lombok.Getter;

import java.util.Arrays;

/**
 * 支持的编程语言。
 *
 * <p>每种语言声明三件事：源码文件名、容器镜像、编译命令、运行命令。
 * 加一门新语言只需要在这里加一个枚举值，判题流程不用动。
 */
@Getter
public enum LanguageEnum {

    /**
     * Java：文件名必须叫 Main.java（类名 Main），与前端 `public class Main` 模板一致
     *
     * <p>镜像用 <b>eclipse-temurin</b>（Eclipse Adoptium 的官方 OpenJDK 构建）
     * 而不是 {@code openjdk} —— 后者是 Docker 官方镜像，官方已宣布停止更新，
     * 且国内网络下 temurin 更容易拉到。JDK 21 自带 javac，可直接编译。
     *
     * <p>⚠️ 镜像 tag 必须和本地已拉取的<b>完全一致</b>（这里是 {@code 21-jdk}）。
     * 写成 {@code 21-jdk-jammy} 这类近似 tag 会触发重新拉取，网络不通时直接判题失败。
     */
    JAVA("java", "Main.java", "eclipse-temurin:21-jdk",
            "javac -encoding UTF-8 -d /app /app/Main.java", "java -cp /app Main"),

    /**
     * C++
     */
    CPP("cpp", "main.cpp", "gcc:13",
            "g++ -o /app/main /app/main.cpp", "/app/main"),

    /**
     * Go。用 alpine 镜像，编译产物是静态二进制
     */
    GO("go", "main.go", "golang:1.22-alpine",
            "go build -o /app/main /app/main.go", "/app/main"),
    ;

    private final String value;

    private final String fileName;

    private final String image;

    private final String compileCommand;

    private final String runCommand;

    LanguageEnum(String value, String fileName, String image,
                 String compileCommand, String runCommand) {
        this.value = value;
        this.fileName = fileName;
        this.image = image;
        this.compileCommand = compileCommand;
        this.runCommand = runCommand;
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
