package com.surprising.aeron.bootstrap;

import com.surprising.aeron.protocol.CoreResultCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扫描本服务生产 Java 源码中直接写出的业务拒绝码，要求已有同名协议结果码，避免已知理由被映射成泛化错误。正则只覆盖字面量构造形式，不证明动态生成的所有拒绝码都有登记。
 */
class CoreBusinessRejectionCodeTest {
    /**
     * 从 Maven 模块当前目录遍历 src/main/java，匹配 new CoreStateRejectedException 后的全大写字面量，逐个核对协议映射仍返回同名结果；不解析完整 Java 语法。
     */
    @Test
    void everyLiteralBusinessRejectionHasARegisteredWireCode() throws Exception {
        // 匹配拒绝异常直接构造且首参为大写下划线字面量的正则
        var pattern = Pattern.compile("new CoreStateRejectedException\\(\\s*\"([A-Z_]+)\"");
        try (/* 遍历本模块生产源码树的文件流，资源退出时关闭 */ var files = Files.walk(Path.of("src/main/java"))) {
            for (var /* 本次读取并检查字面量拒绝码的 Java 文件 */ file : files.filter(/* 文件筛选回调当前路径，只保留 .java 结尾 */ path -> path.toString().endsWith(".java")).toList()) {
                // 当前源码文本中搜索直接写出拒绝码的正则游标
                var matcher = pattern.matcher(Files.readString(file));
                while (matcher.find()) {
                    // 正则捕获的原业务拒绝码，要求能映射到同名协议结果
                    String code = matcher.group(1);
                    assertThat(CoreResultCode.fromRejectionCode(code).name())
                            .as("Business rejection in %s must not lose its reason", file)
                            .isEqualTo(code);
                }
            }
        }
    }
}
