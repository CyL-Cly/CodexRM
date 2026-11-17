# PHP 免费虚拟主机中继探针

用于判断普通 PHP 主机能否承载 **HTTP 短轮询中继**，不是生产中继，不会修改现有 CodexRM 客户端或连接服务器。

## 已配置的本地文件

本次生成的可上传副本位于 `.work/php-relay-probe/relay-probe.php`，数据库配置已填写。该目录被 Git 忽略。模板中的占位符及本说明均不含数据库密码。

1. 仅将 `.work/php-relay-probe/relay-probe.php` 上传到虚拟主机可执行 PHP 的目录，使用 HTTPS 访问。
2. 从同目录 `access-key.txt` 复制访问密钥，在页面粘贴，点击“开始基本测试”。
3. 运行同目录的 `check-probe.exe`，输入刚才的 PHP 地址。它使用原生 HTTP 客户端，不执行网页 JavaScript，不带浏览器 Cookie，验证未来 Go Agent 能否访问。
4. 页面可以下载浏览器报告；程序客户端默认将报告写入当前目录的 `php-relay-probe-native-report.json`。报告不包含数据库密码或访问密钥。
5. 将报告用于评估；测试结束删除主机上的 PHP 探针。

命令行也可以显式指定：

```powershell
.\.work\php-relay-probe\check-probe.exe -url 'https://你的主机/relay-probe.php' -key-file '.\.work\php-relay-probe\access-key.txt' -report '.\.work\php-relay-probe\host-report.json'
```

## 测试范围

- POST JSON、Unicode、随机 nonce、Authorization / 自定义请求头、错误密钥拒绝。
- 16 KiB / 256 KiB / 1 MiB 请求大小及 SHA-256 完整性。
- PHP 版本、PDO 驱动、JSON、文件锁、随机数及相关运行限制。没有使用 `phpinfo()` 或导出环境变量。
- MySQL：连接、随机命名的 InnoDB 测试表、预处理插入、UTF-8 / 64 KiB 内容读取、事务回滚。
- MySQL / 文件：通过独立 HTTP 请求完成 16 条双向消息的写入、读取、游标过滤；分组启动 4 个并发写入请求，检查消息内容、序号唯一性及路由。服务端是否实际同时执行取决于主机的 worker 数；单 worker 的通过结果不能证明并行文件锁有效。
- 文件存储测试通过时也会检查 `flock`；优先使用私有临时目录，回退到脚本附近的独立目录。数据文件带 PHP 退出前缀。
- 可选 2 秒等待及流式输出检查。流式响应被缓冲不会导致短轮询方案失败。
- 原生客户端遇到 HTML 浏览器验证、重定向或错误页会报告失败，不尝试绕过主机验证。

至少一种持久化后端可用是基本条件。使用 MySQL 的计划还需要对应 MySQL 测试通过，文件测试通过不能替代它。请求头缺失列为警告；当前 CodexRM 使用请求头鉴权，如主机剥离请求头，需要调整协议。探针使用独立的 JSON 密钥以便继续诊断。

文件 / MySQL 测试在 `finally` / `defer` 中清理本轮随机测试数据。若断网或关闭页面导致清理未执行，可以用相同 `run`、`backend`、密钥发送 `queue_cleanup`；表名严格限制为 `codexrm_probe_<32位随机十六进制>`。文件队列只为环境诊断设计，没有生产级的崩溃恢复、TTL、确认及重投机制。

“通过”只证明本轮基本能力，不代表主机条款允许中继、免费请求额度够用、长时间稳定或支持 WebSocket。浏览器测试不能替代原生客户端测试。探针不会自动尝试 SSH、常驻进程、后台任务或外部网站访问；这些不是短轮询中继的必需条件。

## 从模板手工配置

复制 `relay-probe.php` 到私有目录，将顶部 `__PROBE_*__` 占位符替换为随机访问密钥和自己的 MySQL 配置。不要将含真实配置的副本提交到仓库。密钥至少 24 字符；建议使用 32 字节随机数的十六进制表示。

原生测试工具只使用 Go 标准库：

```powershell
go build -o .\.work\php-relay-probe\check-probe.exe .\tools\php-relay-probe
```
