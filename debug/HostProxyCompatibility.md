# 每应用代理配置与验证

## 应用内设置

打开使用此运行时的应用后，在 macOS 左上角的应用菜单选择“网络代理…”，或按 `⌘,`。选择沿用当前代理设置、直连、HTTP 或 SOCKS5；手动模式填写代理服务器地址和端口，保存后退出并重新打开该应用生效。例如代理服务位于本机的 8080 端口时，可填写 `127.0.0.1`、`8080`。这只是连接该代理，不保证其当前出口能访问指定的 Google 服务。

每个应用使用自己的数据根和配置。设置窗口不修改代理软件规则或节点；取消会保留已保存的配置。配置保存采用同目录临时文件和原子替换，文件权限为 0600。无效配置可以通过菜单重新设置，保存前不会被自动改成直连。

配置在应用运行时启动时读取一次，保存后下次启动生效。配置根优先采用 `-Dmacapk.android.root=<resolved root>`，缺失时使用 `MACAPK_ANDROID_ROOT`，文件为该根下的 `.macapk/proxy.properties`。

```properties
mode=http
host=127.0.0.1
port=6789
```

默认不启用手动代理；手动连接的地址和端口取自保存的配置。配置不会修改 macOS 系统代理、代理软件的规则或节点。

## 模式

- 无配置文件：保留原来的 Java 默认选择器和代理属性，MCS 保留原来的直接传输。
- `system`：沿用启动时的代理属性和选择器。宿主 GMS HTTP、MCS 使用启动快照；默认 JRE 选择器的系统查询通过小型 JNI 桥直接调用系统查询，避免应用后改的 Java 代理属性覆盖它。自定义的启动选择器保留其对象和自身选择逻辑。
- `direct`：标准 Java 网络选择 DIRECT，并清除旧代理地址属性；宿主 GMS HTTP、MCS 明确直连。
- `http`：标准 Java HTTP/HTTPS 使用指定 HTTP 代理。宿主 GMS HTTP 显式采用同一路由，MCS 在代理上建立 HTTP CONNECT 隧道后，再执行原有目标服务器 TLS 握手。
- `socks5`：标准 Java HTTP/HTTPS 使用指定 SOCKS5 代理；MCS 执行 SOCKS5 无鉴权 CONNECT 后，再执行原有 TLS 握手。IPv4、IPv6 字面地址分别使用 ATYP1、ATYP4，域名使用 ATYP3。

显式保存 `system` 配置后，MCS 才采用启动时的 HTTPS 代理路由；没有文件时保持历史行为。系统模式保留启动时的显式配置及原生系统选择能力，不解析 PAC 脚本或替换用户的系统代理。

手动模式同步 `http/https/socks` 代理属性，使 `android.net.Proxy` 等读取属性的 API 与默认选择器一致。旧的其他代理地址与宽泛例外列表不会残留。宿主 GMS 在手动模式或 DIRECT 模式使用自己的捕获策略，不受应用后改的代理属性或默认选择器影响。

直接访问 localhost、127 段及 IPv6 loopback 地址时默认直连，供本地服务使用。例外按初始目标地址判断，与代理服务器本身位于本机不冲突。显式代理连接的 HTTP 重定向沿用当前连接的代理；从远程 URL 重定向到 loopback 时不会重新应用本机地址例外。手动代理失败时不附加 DIRECT 路由；HTTP CONNECT 的非 2xx、SOCKS 拒绝、EOF 和超时均报告失败并关闭套接字。

## 配置约束

文件最多 8192 字节，内容为 ASCII `key=value`，允许空行、首部 `#`/`!` 注释及字段两侧空白。仅接受 `mode`、`host`、`port`；重复或未知字段拒绝。`mode` 必须存在；手动模式必须提供 ASCII 主机名或有效 IPv6 地址，以及 1–65535 的十进制端口。IPv6 可带方括号，内部使用去括号的地址。system/direct 可保留 host/port 字段，但不参与路由。

没有用户名或密码配置。无效或不可读的配置会明确记录错误并阻断远程选择，不会默认为直连；loopback 服务仍可访问，便于修复配置。

证书信任、HTTPS 主机名校验、应用 TLS 工厂和 pinning 保持原有行为。该功能覆盖标准 Java HTTP/HTTPS、Mac.apk 的 GMS HTTP 和 MCS；应用自行管理的原生网络不由 Java 选择器接管。代理设置正确不代表选定节点能访问 Google，更不代表 FCM 注册或实际账号认证成功。

## 本地回归

```sh
debug/check_host_proxy.sh
debug/check_proxy_settings.sh
```

代理脚本局部编译生产 Java 类和生产 JNI 桥，在真实补丁 JRE 中启动独立测试进程。HTTP CONNECT、SOCKS5 和 TLS 服务器均位于 loopback；保留目标证书与主机名验证。测试代理只把预先允许的测试目标映射到本地服务，包括真实 `McsClient.openSocket` 使用的 `mtalk.google.com` 名称，不与 Google 建立连接、不注册设备或使用实际推送凭据。

覆盖配置解析、无文件兼容、DIRECT、HTTP/SOCKS 的 Java HTTP/HTTPS 和宿主 GMS 请求、真实 MCS TLS 握手、启动系统属性快照、原生系统选择器查询、应用后改属性/选择器、代理拒绝无直连回退、握手超时、SOCKS IPv6 ATYP4、loopback 例外及配置根优先级。原有 HTTPS 回归继续覆盖信任库、主机名和应用工厂保留。
