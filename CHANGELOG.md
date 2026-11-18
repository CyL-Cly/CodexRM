# 更新日志

本项目的重要变更记录在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- P2P 在多台 STUN 显示固定端口步长时优先探测这些预测端口；步长不可用时仍按不可预测映射做邻域和多 socket 探测。

- 配对浏览器以完整 WebGL GPU 型号命名，已配对设备在重新连接时自动更新，无法检测时回退为系统和浏览器名称；设备列表支持长名称换行。
- Windows 原生 Agent 设置页：托盘打开、随机回环端口持续监听、连接配置热切换、二维码配对和已配对设备管理；未配置 RM URL 时启动自动打开设置。
- Go 原生安装及卸载，不再依赖 PowerShell；安装时不设置 RM URL，仅创建指向 Agent exe 的 `--restart` 桌面快捷方式。
- 独立更新服务器设置，默认折叠，默认地址 `https://codexrm.dagongren.tech/`。
- `install.cmd` 显示当前版本、检查在线更新及手动下载安装；通过 RM 的 `/update.json` 查询清单、从 Cloudreve 下载 ZIP，一键递增版本、构建失败回退版本和上传失败重试。
- Windows 客户端每日自动更新、ZIP 大小与 SHA-256 校验、静默替换重启及失败回滚；服务端更新清单与下载路由，打包时生成与 SmsForwarder 同格式的 `update.json`。
- Windows 交互式安装与设置：探测并显示 Codex CLI、设置 RM URL、记录安装位置、启用或删除当前用户登录自启动。
- 准备首个公开开源版本：项目文档、MIT 许可证、贡献指南、安全政策、社区模板与持续集成。
