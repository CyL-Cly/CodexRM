# wxbot 原生功能对照

对照基于 wxbot 管理前端提交 `bb30f98204fd9aacbf84b4c4ab717254a5e3de37` 的页面和生成 API，以及集成管理后端提交 `76d9a15625bb4ae528512526fb75d38678a83d89` 的路由定义。所有 CodexRM 入口都需要 Agent 设置页管理员授权。表中的权限是 Agent 调用 wxbot 时所需的额外权限。

## 2026-11-21 路由对照

按本地 `wechat-robot-admin-backend/router/router.go` 核对了 141 条路由注册。127 项操作使用 Agent 的功能管理目录。账号创建、扫码、滑块、验证码、设备迁移和后台接管有专用入口。手动发送语音所用的豆包 TTS 回调继续由后台服务处理。

新增入口位于「后台账号与接口」「机器人维护」「运行诊断」。机器人创建支持选择协议版本和填入代理。扫码支持 iPad、Windows、Mac 和车载微信。iPhone Data62、Android A16 及设备数据导入位于「设备登录与迁移」。

私聊、群聊、群总结、朋友圈自动互动和记忆提取使用 Codex。知识库和会话记忆检索结果可进入 Codex 的对话上下文。群总结沿用原有定时及文本或图片设置。朋友圈沿用自动点赞、评论和名单设置，并通过 Codex 分析文本、图片或视频首帧。群管理员可在微信里使用 `#切换聊天模型 模型名` 更新相应群的 Codex 模型。自动任务通过独立的 Codex 会话运行。

通过新客户端报告的 `codexOnly` 能力确认运行版本。已有安装可先接管管理，再使用维护页升级客户端。Agent、中继、管理后端及机器人客户端需要配套版本。

路由逐项对照属于代码核对。2026-11-21 已在本地模拟后台验证令牌显示、授权二维码、镜像拉取进度和诊断下载，并验证机器人经中继启动、完成和关闭 Codex 自动任务。真实扫码、公众号授权、朋友圈及外部服务操作待联机验收。以下表格保留此前的实机记录。

## 专用入口

| 上游功能 | CodexRM 入口 | 所需权限 | 验收状态 |
| --- | --- | --- | --- |
| 管理后台登录 | 接管已有安装 | wxbot 管理凭据 | 隔离环境实测，凭据由 Agent 保存 |
| 机器人列表与创建 | 机器人账号与登录 | wxbot 管理会话 | 隔离环境实测创建首个账号 |
| 机器人详情和协议状态 | 机器人账号与登录 | wxbot 实例所有者 | 实测协议容器及客户端能力检测，完整账号资料编辑待实测 |
| 登录二维码与轮询 | 机器人账号与登录 | wxbot 实例所有者 | 实测生成二维码并轮询待扫码状态 |
| 六位验证码 | 机器人账号与登录 | wxbot 实例所有者 | 已接入，需手机端验证 |
| 人工滑块 | 扫码登录 | wxbot 管理会话 | 上游提供独立验证组件，本轮待接真实挑战样本 |
| 退出微信 | 机器人账号与登录 | wxbot 实例所有者 | 已接入，待测试账号 |
| 登录数据导出 | 机器人账号与登录 → 导出设备登录数据 | 实例所有者及单独确认 | 已接入，待已登录账号 |
| 重启协议端或客户端 | 微信机器人功能管理 → 机器人维护 | 实例所有者 | 容器级启停、接管客户端替换和受管客户端升级失败回滚已实测 |
| 测试消息收发 | 机器人账号与登录 → 测试消息收发 | 实例所有者及已登录账号 | 已接入，待测试好友 |
| 好友、群、群成员同步 | 通讯录 | 所选的已登录机器人 | 联系人分页和群表单通过夹具，真实通讯录待扫码 |
| CodexRM 好友、群、工作区、命令、配对及解绑 | 联系人和群设置 | 已登录机器人，Agent 校验会话归属 | 群默认设置、会话路由及文件隔离通过夹具和定向测试，真实群聊待扫码 |
| 朋友圈入口的 CodexRM 接入设置 | 联系人和群设置 | 已登录的所选机器人 | 专用入口使用同一份客户端配对设置 |
| 后台登录、OAuth、查看当前用户及退出后台 | 服务总览接管、后台账号与接口 | Agent 管理员 | 本地模拟界面通过，公众号实机待验 |
| 后台用户 API Token 刷新 | 后台账号与接口 | wxbot 管理会话 | 本地模拟界面通过 |

## 管理操作目录

此前接入的 117 项使用「微信机器人功能管理 → 对应模块 → 对应操作」。**已接入**表示目录、原生表单和 API 转发已实现。**待实机**表示还需要逐项使用测试机器人读写验证。需要对象存储、模型、MCP 或其他外部服务的操作还需要对应的测试凭据。没有发现对应页面调用的接口标记为「接口直达」。

### 群插件设置

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 获取群设置 | `GET /chat-room-settings` | `settings/ChatRoomSettings.tsx` | 实例所有者 | 已接入，待实机 |
| 保存群设置 | `POST /chat-room-settings` | `settings/ChatRoomSettings.tsx` | 实例所有者 | 已接入，待实机 |

### 群管理

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 设置群公告 | `POST /chat-room/announcement` | `contact/ChatRoomAnnouncementChange.tsx` | 实例所有者 | 已接入，待实机 |
| 创建群聊 | `POST /chat-room/create` | `contact/ChatRoomCreateConfirm.tsx`、`robot/NewRobot.tsx` | 实例所有者 | 已接入，待实机 |
| 邀请群成员 | `POST /chat-room/invite` | `contact/ChatRoomInvite.tsx` | 实例所有者 | 已接入，待实机 |
| 同意入群邀请 | `POST /chat-room/join` | `system-message/ChatRoomJoin.tsx` | 实例所有者 | 已接入，待实机 |
| 获取群成员详情 | `GET /chat-room/member` | `chat-room/ChatRoomMemberSettings.tsx` | 实例所有者 | 已接入，待实机 |
| 更新群成员配置 | `POST /chat-room/member` | `chat-room/ChatRoomMemberSettings.tsx` | 实例所有者 | 已接入，待实机 |
| 获取群成员列表 | `GET /chat-room/members` | `chat/index.tsx`、`chat-room/ChatRoomMember.tsx`、`components/send-message` | 实例所有者 | 已接入，待实机 |
| 删除群成员 | `DELETE /chat-room/members` | `chat-room/ChatRoomMemberRemove.tsx` | 实例所有者 | 已接入，待实机 |
| 同步群成员 | `POST /chat-room/members/sync` | `chat-room/ChatRoomMember.tsx` | 实例所有者 | 已接入，待实机 |
| 设置群名称 | `POST /chat-room/name` | `contact/ChatRoomNameChange.tsx` | 实例所有者 | 已接入，待实机 |
| 获取未退群成员列表 | `GET /chat-room/not-left-members` | `contact/ChatRoomInvite.tsx`、`settings/ChatRoomSettings.tsx` | 实例所有者 | 已接入，待实机 |
| 退出群聊 | `DELETE /chat-room/quit` | `contact/ChatRoomQuit.tsx` | 实例所有者 | 已接入，待实机 |
| 设置群备注 | `POST /chat-room/remark` | `contact/ChatRoomRemarkChange.tsx` | 实例所有者 | 已接入，待实机 |

### 聊天记录

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 下载聊天文件 | `GET /chat/file/download` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 获取聊天记录 | `GET /chat/history` | `chat/index.tsx` | 实例所有者 | 已接入，待实机 |
| 下载聊天图片 | `GET /chat/image/download` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 下载聊天视频 | `GET /chat/video/download` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 下载聊天语音 | `GET /chat/voice/download` | 接口直达 | 实例所有者 | 已接入，待实机 |

### 好友

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 删除好友 | `DELETE /contact/friend` | `contact/FriendDelete.tsx` | 实例所有者 | 已接入，待实机 |
| 发送好友申请 | `POST /contact/friend/add` | `contact/SearchFriends.tsx` | 实例所有者 | 已接入，待实机 |
| 从群成员发送好友申请 | `POST /contact/friend/add-from-chat-room` | `chat-room/ChatRoomMemberFriend.tsx` | 实例所有者 | 已接入，待实机 |
| 通过好友验证 | `POST /contact/friend/pass-verify` | `system-message/FriendPassVerify.tsx` | 实例所有者 | 已接入，待实机 |
| 设置好友备注 | `POST /contact/friend/remark` | `contact/FriendRemarkChange.tsx` | 实例所有者 | 已接入，待实机 |
| 搜索好友 | `POST /contact/friend/search` | `contact/SearchFriends.tsx` | 实例所有者 | 已接入，待实机 |
| 获取联系人列表 | `GET /contact/list` | `contact/ChatRoomCreateConfirm.tsx`、`contact/ChatRoomInvite.tsx`、`contact/index.tsx` | 实例所有者 | 已接入，待实机 |
| 同步联系人 | `POST /contact/sync` | `contact/index.tsx` | 实例所有者 | 已接入，待实机 |

### 好友设置

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 获取好友设置 | `GET /friend-settings` | `settings/FriendSettings.tsx` | 实例所有者 | 已接入，待实机 |
| 保存好友设置 | `POST /friend-settings` | `settings/FriendSettings.tsx` | 实例所有者 | 已接入，待实机 |

### 插件与全局设置

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 获取全局设置 | `GET /global-settings` | `settings/ChatRoomSettings.tsx`、`settings/FriendSettings.tsx`、`settings/index.tsx` | 实例所有者 | 已接入，待实机 |
| 保存全局设置 | `POST /global-settings` | `settings/index.tsx` | 实例所有者 | 已接入，待实机 |

### 图片知识库

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 获取图片知识分类 | `GET /image-knowledge/categories` | `knowledge-base/KnowledgeBase.tsx`、`settings/ChatRoomSettings.tsx` | 实例所有者 | 已接入，待实机 |
| 新增图片知识文档 | `POST /image-knowledge/document` | `text-knowledge-base/KnowledgeDocumentEditor.tsx` | 实例所有者 | 已接入，待实机 |
| 删除图片知识文档 | `DELETE /image-knowledge/document` | `text-knowledge-base/KnowledgeDocumentActions.tsx` | 实例所有者 | 已接入，待实机 |
| 获取图片知识文档列表 | `GET /image-knowledge/documents` | `text-knowledge-base/KnowledgeDocument.tsx` | 实例所有者 | 已接入，待实机 |
| 重建图片知识库索引 | `POST /image-knowledge/reindex` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 按图片搜索图片知识 | `POST /image-knowledge/search/image` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 按文本搜索图片知识 | `POST /image-knowledge/search/text` | 接口直达 | 实例所有者 | 已接入，待实机 |

### 文本知识库

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 获取知识分类 | `GET /knowledge/categories` | `knowledge-base/KnowledgeBase.tsx`、`settings/ChatRoomSettings.tsx` | 实例所有者 | 已接入，待实机 |
| 更新知识分类 | `PUT /knowledge/category` | `knowledge-base/TextKnowledgeBaseEditor.tsx` | 实例所有者 | 已接入，待实机 |
| 创建知识分类 | `POST /knowledge/category` | `knowledge-base/TextKnowledgeBaseEditor.tsx` | 实例所有者 | 已接入，待实机 |
| 删除知识分类 | `DELETE /knowledge/category` | `knowledge-base/TextKnowledgeBaseActions.tsx` | 实例所有者 | 已接入，待实机 |
| 更新知识文档 | `PUT /knowledge/document` | `text-knowledge-base/KnowledgeDocumentEditor.tsx` | 实例所有者 | 已接入，待实机 |
| 新增知识文档 | `POST /knowledge/document` | `text-knowledge-base/KnowledgeDocumentEditor.tsx` | 实例所有者 | 已接入，待实机 |
| 删除知识文档 | `DELETE /knowledge/document` | `text-knowledge-base/KnowledgeDocumentActions.tsx` | 实例所有者 | 已接入，待实机 |
| 禁用知识文档 | `POST /knowledge/document/disable` | `text-knowledge-base/KnowledgeDocumentActions.tsx` | 实例所有者 | 已接入，待实机 |
| 启用知识文档 | `POST /knowledge/document/enable` | `text-knowledge-base/KnowledgeDocumentActions.tsx` | 实例所有者 | 已接入，待实机 |
| 获取知识文档列表 | `GET /knowledge/documents` | `text-knowledge-base/KnowledgeDocument.tsx` | 实例所有者 | 已接入，待实机 |
| 重建文本知识库索引 | `POST /knowledge/reindex` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 搜索知识库 | `POST /knowledge/search` | 接口直达 | 实例所有者 | 已接入，待实机 |

### MCP

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 获取 MCP 服务详情 | `GET /mcp-server` | `mcp-servers/MCPServerEditor.tsx` | 实例所有者 | 已接入，待实机 |
| 更新 MCP 服务 | `PUT /mcp-server` | `mcp-servers/MCPServerEditor.tsx` | 实例所有者 | 已接入，待实机 |
| 创建 MCP 服务 | `POST /mcp-server` | `mcp-servers/MCPServerEditor.tsx` | 实例所有者 | 已接入，待实机 |
| 删除 MCP 服务 | `DELETE /mcp-server` | `mcp-servers/MCPServer.tsx` | 实例所有者 | 已接入，待实机 |
| 禁用 MCP 服务 | `POST /mcp-server/disable` | `mcp-servers/MCPServer.tsx`、`skills/Skill.tsx` | 实例所有者 | 已接入，待实机 |
| 启用 MCP 服务 | `POST /mcp-server/enable` | `mcp-servers/MCPServer.tsx`、`skills/Skill.tsx` | 实例所有者 | 已接入，待实机 |
| 获取 MCP 服务列表 | `GET /mcp-server/list` | `contact/ChatRoomCreateConfirm.tsx`、`contact/ChatRoomInvite.tsx`、`contact/index.tsx` | 实例所有者 | 两个隔离机器人读取通过，外部凭据场景待验 |
| 获取 MCP 服务工具列表 | `GET /mcp-server/tools` | `mcp-servers/MCPServer.tsx` | 实例所有者 | 已接入，待实机 |

### 记忆

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 保存记忆 | `POST /memory` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 删除记忆 | `DELETE /memory` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 搜索记忆 | `POST /memory/search` | 接口直达 | 实例所有者 | 已接入，待实机 |

### 消息收发

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 撤回消息 | `POST /message/revoke` | `chat/components` | 实例所有者 | 已接入，待实机 |
| 发送 AI 语音消息 | `POST /message/send/ai/tts` | `components/send-message` | 实例所有者 | 已接入，待实机 |
| 发送文件分片 | `POST /message/send/file` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 发送图片消息 | `POST /message/send/image` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 发送长文本消息 | `POST /message/send/longtext` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 发送文本消息 | `POST /message/send/text` | `components/send-message` | 实例所有者 | 已接入，待实机 |
| 发送视频消息 | `POST /message/send/video` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 发送语音消息 | `POST /message/send/voice` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 获取语音音色列表 | `GET /message/timbre` | `components/send-message` | 实例所有者 | 已接入，待实机 |

### 朋友圈

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 评论朋友圈 | `POST /moments/comment` | `moments/CommentMoment.tsx`、`moments/index.tsx`、`moments/SpecifiContactMomentDetail.tsx` | 实例所有者 | 已接入，待实机 |
| 下载朋友圈媒体 | `GET /moments/down-media` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 获取朋友圈用户页详情 | `GET /moments/get-detail` | `moments/SpecifiContactMomentList.tsx` | 实例所有者 | 已接入，待实机 |
| 获取朋友圈单条详情 | `GET /moments/get-id-detail` | `moments/SpecifiContactMomentDetail.tsx` | 实例所有者 | 已接入，待实机 |
| 获取朋友圈列表 | `GET /moments/list` | `contact/ChatRoomCreateConfirm.tsx`、`contact/ChatRoomInvite.tsx`、`contact/index.tsx` | 实例所有者 | 已接入，待实机 |
| 操作朋友圈 | `POST /moments/operate` | `moments/index.tsx`、`moments/SpecifiContactMomentDetail.tsx` | 实例所有者 | 已接入，待实机 |
| 发布朋友圈 | `POST /moments/post` | `moments/PostMoment.tsx` | 实例所有者 | 已接入，待实机 |
| 设置朋友圈隐私 | `POST /moments/privacy-settings` | 接口直达 | 实例所有者 | 已接入，待实机 |
| 获取朋友圈设置 | `GET /moments/settings` | `moments/MomentSettings.tsx` | 实例所有者 | 已接入，待实机 |
| 保存朋友圈设置 | `POST /moments/settings` | `moments/MomentSettings.tsx` | 实例所有者 | 已接入，待实机 |
| 上传朋友圈媒体 | `POST /moments/upload-media` | 接口直达 | 实例所有者 | 已接入，待实机 |

### 对象存储

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 获取 OSS 设置 | `GET /oss-settings` | `oss-settings/index.tsx` | 实例所有者 | 两个隔离机器人读取通过，外部凭据场景待验 |
| 保存 OSS 设置 | `POST /oss-settings` | `oss-settings/index.tsx` | 实例所有者 | 已接入，待实机 |
| 测试已保存的存储桶连接 | `POST /oss-settings/test` | CodexRM 原生入口 | 实例所有者 | 已接入，只读探测，真实存储桶待提供测试凭据 |

### Skills

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 获取技能列表 | `GET /skills` | `skills/index.tsx` | 实例所有者 | 两个隔离机器人读取通过，外部凭据场景待验 |
| 禁用技能 | `POST /skills/disable` | `mcp-servers/MCPServer.tsx`、`skills/Skill.tsx` | 实例所有者 | 已接入，待实机 |
| 启用技能 | `POST /skills/enable` | `mcp-servers/MCPServer.tsx`、`skills/Skill.tsx` | 实例所有者 | 已接入，待实机 |
| 设置技能环境变量 | `POST /skills/envs` | `skills/SkillEnvs.tsx` | 实例所有者 | 已接入，待实机 |
| 安装技能 | `POST /skills/install` | `skills/InstallSkill.tsx`、`skills/SkillsMarket.tsx` | 实例所有者 | 已接入，待实机 |
| 卸载技能 | `DELETE /skills/uninstall` | `skills/Skill.tsx` | 实例所有者 | 已接入，待实机 |
| 更新技能 | `PUT /skills/update` | `robot/RobotEditor.tsx`、`skills/Skill.tsx`、`skills/SkillsMarket.tsx` | 实例所有者 | 已接入，待实机 |

### 系统消息

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 获取近一个月系统消息 | `GET /system-messages` | `system-message/index.tsx` | 实例所有者 | 已接入，待实机 |
| 批量标记系统消息已读 | `POST /system-messages/mark-as-read` | `system-message/MessageList.tsx` | 实例所有者 | 已接入，待实机 |

### 系统提示词

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 获取系统提示词列表 | `GET /system-prompts` | `components/SystemPromptEditor.tsx`、`system-prompts/index.tsx` | 实例所有者 | 两个隔离机器人读取通过，外部凭据场景待验 |
| 更新系统提示词 | `PUT /system-prompts` | `system-prompts/SystemPromptEditor.tsx` | 实例所有者 | 已接入，待实机 |
| 创建系统提示词 | `POST /system-prompts` | `system-prompts/SystemPromptEditor.tsx` | 实例所有者 | 已接入，待实机 |
| 删除系统提示词 | `DELETE /system-prompts` | `system-prompts/SystemPromptActions.tsx` | 实例所有者 | 已接入，待实机 |
| 获取系统提示词详情 | `GET /system-prompts/detail` | 接口直达 | 实例所有者 | 已接入，待实机 |

### 机器人设置

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 获取系统设置 | `GET /system-settings` | `system-settings/index.tsx` | 实例所有者 | 两个隔离机器人读取通过，外部凭据场景待验 |
| 保存系统设置 | `POST /system-settings` | `system-settings/index.tsx` | 实例所有者 | 已接入，待实机 |

### 容器状态与日志

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 获取机器人容器日志 | `GET /system/robot-container-logs` | `container-log/index.tsx` | 实例所有者 | 已接入，待实机 |
| 获取机器人容器资源统计 | `GET /system/robot-container-stats` | `system-overview/index.tsx` | 实例所有者 | 已接入，待实机 |

### 向量索引

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 重建全部向量索引 | `POST /vector/reindex-all` | `knowledge-base/KnowledgeBase.tsx` | 实例所有者 | 已接入，待实机 |

### 机器人维护

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 读取协议端状态 | `GET /robot/state` | `robot/actions` | 实例所有者 | 已接入，协议容器探测通过 |
| 修改实例名称或代理 | `PUT /robot/update` | `robot/RobotEditor.tsx`、`skills/Skill.tsx`、`skills/SkillsMarket.tsx` | 实例所有者 | 所选账号改名成功，另一账号保持原名 |
| 创建并启动客户端容器 | `POST /robot/docker/container/client/start` | `robot/actions` | 实例所有者 | 已接入，容器生命周期验证通过 |
| 创建并启动协议容器 | `POST /robot/docker/container/server/start` | `robot/actions` | 实例所有者 | 已接入，容器生命周期验证通过 |
| 停止并移除客户端容器 | `DELETE /robot/docker/container/client/remove` | `robot/actions` | 实例所有者 | 已接入，容器生命周期验证通过 |
| 停止并移除协议容器 | `DELETE /robot/docker/container/server/remove` | `robot/actions` | 实例所有者 | 已接入，容器生命周期验证通过 |
| 删除机器人实例及其数据 | `DELETE /robot/remove` | `robot/actions` | 实例所有者 | 已接入，待实机 |

### 设备登录与迁移

| 操作 | 上游接口 | 上游页面来源 | wxbot 权限 | 验收状态 |
| --- | --- | --- | --- | --- |
| 导入已有设备登录数据 | `POST /robot/import-login-data` | `robot/actions` | 实例所有者 | 已接入，待实机 |
| 使用支持 Data62 的账号登录 | `POST /robot/login/data62` | `robot/actions` | 实例所有者 | 已接入，待实机 |
| 重新发送登录短信 | `POST /robot/login/data62-sms-again` | `robot/actions` | 实例所有者 | 已接入，待实机 |
| 提交登录短信验证码 | `POST /robot/login/data62-sms-verify` | `robot/actions` | 实例所有者 | 已接入，待实机 |
| 使用支持 A16 的账号登录 | `POST /robot/login/a16` | `robot/actions` | 实例所有者 | 已接入，待实机 |
| 小程序二维码授权 | `POST /wxapp/qrcode-auth-login` | 接口直达 | 实例所有者 | 已接入，待实机 |
