# 贡献指南

感谢参与 CodexRM。提交问题或代码前，请先阅读本指南和[行为准则](CODE_OF_CONDUCT.md)。

## 报告问题

- 先搜索已有 Issue，避免重复提交。
- 使用对应的 Issue 模板，提供版本、操作系统、复现步骤、预期与实际结果。
- 日志需脱敏：不要上传令牌、注册密钥、API Key、配对码、私人路径或会话正文。
- 安全问题不要公开发布，请按[安全政策](SECURITY.md)私密报告。

## 开发环境

- Go 1.22+
- Node.js 22+（仅运行前端测试需要）
- 真实模式需要已安装并配置的 Codex 客户端；普通开发和模拟模式不需要模型凭据

```powershell
git clone https://github.com/codexrm/codexrm.git
cd codexrm
.\scripts\build.ps1
```

也可运行 `scripts/dev-mock.ps1` 启动本地模拟服务。不要依赖私有部署、上游参考源码或未纳入仓库的本地文件。

## 提交代码

1. Fork 仓库，并从 `main` 创建主题分支。
2. 保持修改聚焦；行为变化应包含测试和必要的文档更新。
3. 在提交前运行：

   ```powershell
   gofmt -w cmd internal web
   go mod tidy
   go vet ./...
   go test -race ./...
   go build ./cmd/...
   node --test web/app.test.mjs web/sw.test.mjs
   node --check web/app.js
   node --check web/pwa.js
   node --check web/sw.js
   ```

4. 确认 `git diff --check` 无输出，并检查暂存内容中没有凭据或构建产物。
5. 提交 Pull Request，说明动机、主要改动、验证命令及结果；界面变化建议附截图。

不要提交 `.state/`、`.work/`、`bin/`、`dist/`、`.env`、私钥、真实配置、部署记录、`codex/` 上游参考源码或 `ChatGPT*` 参考文件。包含注册密钥的 private 安装包只能私下传输，不能上传到 Issue、Release 或公开制品。

## 代码约定

- Go 代码使用 `gofmt`，错误需明确处理，公共行为通过测试固定。
- 前端无需构建步骤或第三方运行时依赖，保持原生 HTML / CSS / JavaScript。
- 新增配置应有安全默认值和文档；密钥通过环境变量或被忽略的本地状态传递。
- 不在中继中持久化对话正文，不把 Agent、Codex 或供应商凭据发送到网页。

## 许可证

贡献即表示你同意以仓库的 [MIT License](LICENSE) 提供修改。只提交你有权贡献的内容，第三方代码必须保留其原始许可证和版权声明。
