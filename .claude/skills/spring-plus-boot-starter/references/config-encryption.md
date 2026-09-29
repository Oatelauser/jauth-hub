# 配置文件加密（ENC(...)）

包：`io.github.oatelauser.springplus.boot.crypto`。

## 两条命令完成接入

`ConfigEncryptor` 是命令行工具（本地生成密钥与密文后手动贴回配置文件）：

```bash
# 1. 生成 256-bit Base64 密钥
java -cp <classpath> io.github.oatelauser.springplus.boot.crypto.ConfigEncryptor genkey
# → 输出 Base64 密钥，放到环境变量 APP_CONFIG_KEY

# 2. 加密明文（密钥取环境变量 APP_CONFIG_KEY）
java -cp <classpath> ...ConfigEncryptor encrypt 'jdbc:mysql://...密码'
# → 输出 ENC(xJ8f...)

# 或显式传密钥
java -cp <classpath> ...ConfigEncryptor encrypt <key> <明文>
```

配置文件中书写：

```yaml
spring:
  datasource:
    password: ENC(xJ8f...)        # 环境准备阶段自动解密
  plus:
    third-party-secret: ENC(a9B2...)
```

`decrypt` 子命令用于换密钥批量重加密时辅助验证。

## 机制

- `EncryptedPropertyEnvironmentPostProcessor`（注册于 `spring.factories` 的 `EnvironmentPostProcessor`）在**环境准备阶段**解密——早于所有 Bean 初始化，业务侧零感知
- 判定规则：值形如 `ENC(...)` 才处理（`ConfigCipher.isEncrypted`）
- `ConfigCipher` 静态工具：`generateKey()` / `encrypt(base64Key, plaintext)` / `decrypt(base64Key, encryptedValue)`——程序内加解密（如管理后台）直接复用

## 密钥管理约定

- 密钥**不进代码库**：环境变量 `APP_CONFIG_KEY`（容器 K8s Secret / 交付时注入）
- 密文可以进库（yml 里的 `ENC(...)` 无密钥不可读）
- 换密钥流程：新密钥 encrypt 全量敏感项 → 更新 APP_CONFIG_KEY → 重启

## 陷阱

- 解密发生在 `EnvironmentPostProcessor` 阶段——比 `@ConfigurationProperties` 绑定早，所以任何配置位置（含 bootstrap 类属性源）都能用
- ENC 属性源的 `toString` 已脱敏（actuator/env 只暴露键名不泄露明文，V11）：不要自己 new 属性源绕过
- 密钥丢失 = 密文不可恢复，换密钥前先验证 decrypt
