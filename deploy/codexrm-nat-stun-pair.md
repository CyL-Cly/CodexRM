# 自建双节点 NAT 诊断服务

在两台各有一个公网 IPv4 的 Linux 主机上运行 `codexrm-nat-stun`。两台主机提供同一对 UDP 端口。收到要求切换 IP 的 Binding 请求时，本节点把请求交给另一台主机，由对方的 UDP socket 直接向测试端回包。支持 CodexRM 用到的 RFC 3489 和 RFC 5780 IPv4 Binding 探测。

## 部署

1. 在仓库根目录构建与主机架构相对应的 Linux 二进制。ARM64 主机将 `GOARCH` 改为 `arm64`。

   ```sh
   GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -o codexrm-nat-stun ./cmd/codexrm-nat-stun
   ```

2. 在两台主机上将二进制安装到 `/usr/local/bin/codexrm-nat-stun`，将 `deploy/codexrm-nat-stun-pair.service` 安装到 `/etc/systemd/system/`。参考 `deploy/codexrm-nat-stun-pair.env.example` 分别填写 `/etc/codexrm/nat-stun-pair.env`。`LISTEN_IP` 使用实际网卡地址，`PUBLIC_IP` 与 `OTHER_IP` 在两台主机上互换。走公网互联时，`PEER_REMOTE` 填对端的公网 IPv4 和 UDP 端口。也可用两端互通、源地址固定的私网。
3. 生成一次共享密钥，通过已有安全通道复制到对端。两台主机上的文件内容必须一致，权限为 `0600`。节点间时间通过 NTP 同步。

   ```sh
   umask 077
   openssl rand -hex 32 > /etc/codexrm/nat-stun-pair.key
   ```

4. 在云安全组和系统防火墙上放行两个用于诊断的 UDP 端口，默认 `3478` 和 `3479`。节点间 UDP 端口默认 `34780`，入站只允许对端的 IP。云主机走公网地址映射时，客户端和对端看到的出站 UDP 源 IP、端口须与配置一致。使用其他 UDP 端口时，两台的诊断端口配置保持一致。

   ```sh
   systemctl daemon-reload
   systemctl enable --now codexrm-nat-stun-pair.service
   systemctl status --no-pager codexrm-nat-stun-pair.service
   ```

## 验收与接入 RM

从第三个有公网 UDP 路径的主机发起检查。用实际 IPv4 替换下面两个示例地址。检查器会确认两套协议在四组目的地址上都有 Binding 回包，并核实换源后的实际来源。

```sh
codexrm-server --check-nat-stun --p2p-nat-stun '203.0.113.10:3478,198.51.100.10:3478'
```

先把通过检查的节点写入隔离 RM 的 `CODEXRM_P2P_NAT_STUN`。用蜂窝网络 Android 和 Windows Agent 打开 NAT 诊断，分别确认传统 NAT 类型、映射和过滤行为均已出结果。验收通过后，把同样的 `CODEXRM_P2P_NAT_STUN` 写入正式 RM 的 systemd drop-in 并重启 RM。业务直连端点由原 `CODEXRM_P2P_STUN` 配置继续提供。撤销接入时，删除这次添加的诊断配置，执行 `systemctl daemon-reload` 并重启 RM。

对端没有确认发出换源回包时，服务返回 STUN 500，客户端会将该项显示为无法判定。遇到此结果可检查节点 UDP 互通、共享密钥与系统时间。公网探测按源 IP 和总量限速，节点间的请求包含签名和时效校验。
