# 宿主用户验证桥接

`MACAPK_USER_VERIFICATION_HOST=1` 才启用，默认关闭。`LAContext.canEvaluatePolicy` 仅检查实际能力，不产生认证或授权。认证使用系统 LocalAuthentication 窗口，不能通过软件布尔值、Java 回调或预检成功授信。

## 认证类型

- `BIOMETRIC_WEAK`：只选择宿主 Touch ID 等 LocalAuthentication 生物验证，不声称 Android 硬件或认证等级。
- `BIOMETRIC_STRONG` 单独请求：不支持。宿主能力不能据此被认定为 Android Class 3。
- `DEVICE_CREDENTIAL`：使用 `SecAccessControlCreateWithFlags(..., kSecAccessControlDevicePasscode)` 和 `LAContext.evaluateAccessControl(..., LAAccessControlOperationUseItem, ...)`，只有该密码约束实际成功才返回设备凭据类型。不能用同时允许 Touch ID 的 `deviceOwnerAuthentication` 成功来冒充密码成功。
- `STRONG | DEVICE_CREDENTIAL`：仅选择明确允许的设备密码分支。`WEAK | DEVICE_CREDENTIAL` 可先用生物验证，用户选择密码时再转为密码约束。

参考：[Apple evaluateAccessControl](https://developer.apple.com/documentation/localauthentication/lacontext/evaluateaccesscontrol(_:operation:localizedreason:reply:))、本机 SDK 的 `LAContext.h` / `SecAccessControl.h`。能力预检成功不能证明后续密码策略、系统配置或企业策略一定接受实际认证。

## 密钥操作绑定

生产 native 持有认证完成状态、单调时间戳和一次性授权；Java 只接收 request ID，然后消费 native 的完成结果。Java 没有设置成功或授信的入口。

`beginKeyOperation(spi)` 只递增操作代次并撤销旧授权。带 CryptoObject 的请求从 JCE Cipher/Signature 包装器解析到受信任的 AndroidKeyStore SPI，记录同一对象和代次。OS 回调前若操作重新初始化，则请求失败；一次性密钥授权只供该对象、该代次消费一次。未带 CryptoObject 的系统验证不能授权任意一次性密钥。窗口授权同样只来自实际成功，认证类型必须匹配密钥要求。

这些是软件后端的操作访问约束，不代表硬件密钥存储、Secure Enclave 私钥、设备证明或 FIPS 认证。代码不改变企业服务端是否接受该设备。

## 验证

```sh
debug/check_user_verification.sh
debug/check_device_credential_intent.sh
```

该脚本用生产 JNI 源码验证开关、只读能力查询、强认证拒绝、未知 flags、取消/失败/超时单次回调、Java 伪造完成通知不能授信。测试专用 native fixture 只能产生错误码 3/12，没有成功授信钩子，不编入生产。脚本不显示认证窗口、不输入密码、不写入真实账户数据。

真实 Touch ID/密码验证及导入后持久化仍须用户在应用中执行并核对。不能将离线 fixture 或 `canEvaluatePolicy` 当作真实认证通过。

Keyguard 的 `ACTION_CONFIRM_DEVICE_CREDENTIAL` 同样通过宿主系统验证处理。PackageManager 查询与启动均指向该宿主实现的 Settings 组件；启动时同步领取 activity-result 配对，系统认证结束后在恢复 Activity 前交付真实结果。取消、错误和重复终态回调只产生一次 `RESULT_CANCELED`。该 intent 测试使用只能返回失败的 Java transport double，不会调用系统认证或生成密钥授权。
