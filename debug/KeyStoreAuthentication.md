# KeyStore 用户验证约束

认证密钥策略将每次操作授权与限时授权区分处理。KeyProperties 的凭据/生物类型 1/2/3 显式映射到 BiometricManager 的 0x8000/0x000f/0x800f，不混用两套位值。

## 软件约束

- 新 V2 记录把 authentication required、timeout/type、purposes、算法约束、随机化加密要求及 generation 与密钥放在同一条记录中，原子写入。V1 AES/HMAC/RSA 记录继续可读；旧版从未保存用户验证元数据，无法倒推出其原始 UV 策略。
- 新认证私钥与 AES 密钥不通过标准 `getEncoded`、KeyFactory 或序列化导出。RSA/EC 签名、RSA 私钥解密及 AES 数据输出必须经过对应的受限 SPI。
- 授权在生产 native LocalAuthentication 桥内保存。每次初始化和完成会递增 native operation generation，避免旧的系统验证结果授权被重新初始化的 Cipher/Signature。每次操作授权必须匹配 SPI 与 generation，并且只消费一次。时间窗密钥要求真实 OS 认证时间和认证类型。
- `canEvaluatePolicy`、设备能力查询、桌面登录时间和 Java callback 均不授予密钥权限。Touch ID 没有被宣称为 Android Class 3；只要求 strong 的策略仍不满足。明确允许设备凭据的策略由真实密码约束的系统验证提供授权。
- 不支持的认证算法、硬件 presence/confirmation/unlock/on-body、使用次数约束拒绝处理；认证策略写入失败不返回可用密钥；非法计数、尾部字节、重复别名和越界长度均拒绝加载，损坏旧文件不被新记录覆盖。StrongBox 请求也明确拒绝。
- 认证私钥在未授权时不用于签发自己的证书；生成的公钥证书容器带不能验证的占位签名，这是 [Android KeyGenParameterSpec 文档](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)定义的情形，不能当成有效自签或硬件证明。

后台仍是原有文件软件存储，不提供硬件非导出性、Android KeyMint/FIPS 认证或硬件 attestation。不会改写应用要求的安全策略。

认证 AES 的随机化要求会拒绝 ECB 和加密时调用者提供的 IV；解密与显式关闭随机化策略仍可使用原 IV。OAEP 当前仅接受 SHA-1 的 MGF1 与空 label；其他显式 MGF1 key policy 直接拒绝。PSS 要求 MGF1 与消息摘要一致、salt 等于摘要长度、trailer=1。不能执行的参数不会被静默丢弃。

## 回归

```sh
debug/check_keystore_auth.sh
```

脚本使用临时目录及生产 UV JNI，在带 Android 补丁的 JRE 上分别以开关关闭/开启执行跨进程 create/reload。覆盖 V1 保留和 V2 metadata 重载；没有 native grant 时 AES/RSA/EC 拒绝；实际 SPI 解析及 native generation 撤权；无认证策略的 opaque 临时 fixture 执行真实签名和解密往返；软件 KeyInfo；随机 IV/MGF/PSS 参数及策略跨进程重载、不支持约束、写入失败及损坏文件保留。

`NativeKeyStoreAuthOnLoad.mm` 只读检查生产 native operation registry，不提供认证成功或授信 hook。测试不弹认证窗口、不访问网络、不读取真实账户；真实认证成功后的账户注册与迁移仍需实际交互验证。
