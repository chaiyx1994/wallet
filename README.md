# walletgit
**交易所托管钱包后端**（非 C 端用户钱包）。多链统一接口：钱包创建 / 余额查询 / 提现签名广播 / 充值扫链。

> 设计定位：**给交易所用的托管钱包，不是 C 端用户自己管理助记词的钱包**。
> 每条钱包独立密钥对加密存库（AES-256-GCM），**无 master key**——一个钱包泄露只影响一个用户，不雪崩。

## 技术栈
- Java 21 + Spring Boot 2.7
- Spring Data JPA + H2（file 模式持久化）
- web3j 4.12.3（EVM RPC 交互）
- bitcoinj 0.17.1（BTC UTXO 签名）
- AES-256-GCM 私钥加密存储
- Spring ApplicationEvent（扫链 → 账务解耦）

## 项目结构
```
walletgit/
├── pom.xml
├── README.md
└── src/main/java/com/wallet/
    ├── WalletApplication.java
    ├── controller/
    │   ├── WalletController.java        # 创建钱包 / 查余额
    │   └── TransactionController.java   # 提现 / 查交易
    ├── service/
    │   ├── WalletService.java
    │   └── WithdrawalService.java       # 提现主流程（加锁 + 调 adapter）
    ├── crypto/
    │   └── AesGcmEncryptor.java         # 私钥 AES-GCM 加密
    ├── adapter/
    │   ├── ChainAdapter.java            # 多链适配接口
    │   ├── AbstractChainAdapter.java    # 模板方法骨架（final executeWithdrawal）
    │   ├── ChainAdapterFactory.java     # 按 ChainType 路由
    │   └── impl/
    │       ├── EvmChainAdapter.java     # EVM 完整实现（nonce / 签名 / 广播）
    │       ├── BtcChainAdapter.java     # BTC 完整实现（UTXO / blockstream API）
    │       └── SolanaChainAdapter.java  # Solana createWallet + 通用查询（提现实待补）
    └── scanner/                         # 充值扫链模块
        ├── DepositScanner.java          # EVM 主循环（10s tick）
        ├── btc/
        │   ├── BtcBlockFetcher.java     # blockstream.info REST 客户端
        │   └── BtcDepositScanner.java   # BTC 主循环（30s tick）
        ├── fetcher/BlockFetcher.java    # EVM 区块拉取器（web3j）
        ├── parser/TransactionParser.java        # 交易解析（ETH 原生转账）
        ├── matcher/AddressMatcher.java          # 地址匹配（DB 查询）
        ├── reorg/ReorgHandler.java              # 重组检测 + 回滚
        ├── confirm/ConfirmationManager.java     # 确认数跟踪 + publishConfirmed
        ├── emitter/                             # Spring ApplicationEvent 解耦
        └── sync/AddressIndexSyncService.java    # 启动时 Wallet 表 → AddressIndex
```

## 核心架构
```
提现：WithdrawalService → ChainAdapterFactory.getAdapter(chain) → AbstractChainAdapter.executeWithdrawal()
  ├── checkBalance (通用)
  ├── buildRawTransaction (abstract: EVM 签名 / BTC UTXO 构造)
  ├── preBroadcast (hook)
  ├── broadcastTransaction (RPC)
  └── postBroadcast (hook)

扫链：DepositScanner / BtcDepositScanner → BlockFetcher → Parser → AddressMatcher → ReorgHandler → ConfirmationManager → DepositEventPublisher(Spring Event)
```

## 快速开始

### 1. 环境变量

**生产环境必须替换**，开发可以跳过用默认值：

```bash
# AES-GCM 密钥派生（生产必须换）
export WALLET_ENC_SECRET="your-32-byte-secret-here-xxxxxxxxxxxxx"

# EVM RPC（sepolia 测试网）
export EVM_RPC_URL="https://sepolia.infura.io/v3/YOUR_INFURA_KEY"
export EVM_CHAIN_ID="11155111"

# BTC（默认 blockstream.info 公开 API，无需 key）
export BTC_NETWORK="mainnet"
```

### 2. 启动

```bash
cd walletgit

# 默认启动：EVM 扫链 + 提现（BTC 扫链默认关闭）
mvn spring-boot:run

# 同时开启 BTC 扫链
mvn spring-boot:run -Dspring-boot.run.arguments="--wallet.scanner.btc.enabled=true"

# 只跑钱包 + 提现，关闭扫链
mvn spring-boot:run -Dspring-boot.run.arguments="--wallet.scanner.enabled=false --wallet.scanner.btc.enabled=false"

# 从指定历史块补扫 EVM（跳过前面的块，用于追历史充值）
mvn spring-boot:run -Dspring-boot.run.arguments="--wallet.scanner.start-from-block=21000000"
```

启动成功日志：
```
[EvmChainAdapter] initialized (Web3j injected)
[BtcChainAdapter] initialized, network=mainnet, rpc=https://blockstream.info/api
[SolanaChainAdapter] initialized, rpc=https://api.devnet.solana.com
[Scanner] initialized, safeConfirmations=12, startFromBlock=-1
[BTC Scanner] initialized, safeConfirmations=3, startFromBlock=-1
Started WalletApplication in X.XXs
```

### 3. 启动后访问

| 服务 | URL |
|---|---|
| 应用 | http://localhost:8080 |
| H2 Console | http://localhost:8080/h2-console |
| H2 JDBC URL | `jdbc:h2:file:./data/wallet` |

## REST API

### 创建钱包
```bash
# EVM 钱包（sepolia 测试网，返回 0x... 地址）
curl -X POST http://localhost:8080/api/wallets \
  -H 'Content-Type: application/json' \
  -d '{"chainType":"EVM","walletType":"HOT"}'

# BTC 钱包（mainnet，返回 bc1q... bech32 地址，P2WPKH）
curl -X POST http://localhost:8080/api/wallets \
  -H 'Content-Type: application/json' \
  -d '{"chainType":"BTC","walletType":"HOT"}'

# Solana 钱包（devnet 默认，返回 base58 地址）
curl -X POST http://localhost:8080/api/wallets \
  -H 'Content-Type: application/json' \
  -d '{"chainType":"SOLANA","walletType":"HOT"}'
```

### 提现
```bash
# EVM 提现（单位 ETH，会自动换算 wei）
curl -X POST http://localhost:8080/api/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "walletId": 1,
    "toAddress": "0xRecipientAddress",
    "amount": "0.1"
  }'

# BTC 提现（单位 BTC，自动换算 satoshi + 选 UTXO）
curl -X POST http://localhost:8080/api/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "walletId": 2,
    "toAddress": "bc1qRecipientAddress",
    "amount": "0.01"
  }'
```

> Solana 提现待实现（Transaction + Message + 签名 + base64 序列化 → sendRawTransaction）

### 查询
```bash
curl http://localhost:8080/api/wallets            # 列出所有钱包
curl http://localhost:8080/api/wallets/1/balance  # 查实时余额（EVM → web3j / BTC → blockstream / Solana → solanaj）
curl http://localhost:8080/api/transactions       # 交易列表
curl http://localhost:8080/api/transactions/1      # 单笔交易
```

## 钱包架构

### 当前是非 HD（Non-Hierarchical-Deterministic）架构
每条钱包**独立密钥对**，无 master key：
```
Wallet1.privateKey = SecureRandom() → 加密存 DB
Wallet2.privateKey = SecureRandom() → 加密存 DB
Wallet3.privateKey = SecureRandom() → 加密存 DB
// 三个密钥互不相关，A 丢了不影响 B/C
```

### 为什么交易所不需要 HD（master key）
| 场景 | C 端钱包（HD 有意义） | 交易所托管钱包（HD 是风险） |
|---|---|---|
| 用户数量 | 一个用户自己管 | 交易所管几十万用户地址 |
| 密钥保管 | 用户自己管助记词 | 交易所加密存库 |
| 风险模型 | 助记词丢 = 用户资产没（用户自担） | **主密钥泄露 = 全部用户资产归零** |
| 风险隔离 | 不重要 | **极其重要**——一个钱包泄露只影响一个用户，不能雪崩 |
| 恢复需求 | 用户换设备要恢复地址 | 不需要——DB 里解密就能签名 |

结论：交易所托管钱包应该**水平分散、无单点**。walletgit 当前架构正确。

### 密钥对到底是什么
```
密钥对 = { privateKey: 一个随机生成的大数, publicKey: 用 privateKey 算出来的椭圆曲线点 }
私钥能签名（出钱），公钥能验证（谁给我转的）。它们是一对：私钥签的东西，只有这个公钥能验。
```

## 三条链技术对比

| | EVM | BTC | Solana |
|---|---|---|---|
| **密钥类型** | secp256k1 | secp256k1 | **ed25519** |
| **密钥生成** | web3j `Keys.createEcKeyPair()` | bitcoinj `new ECKey()` | solanaj `new Account()` |
| **地址格式** | 0x + hex | bc1q + bech32 (P2WPKH) | base58 |
| **私钥长度** | 32 字节 | 32 字节 | **64 字节**（seed32 + pubkey32） |
| **单位** | wei / ETH (10^18) | satoshi / BTC (10^8) | lamport / SOL (10^9) |
| **nonce** | ✅ 有，每次 +1 | ❌ 无（UTXO） | ❌ 无（recentBlockhash） |
| **safeConfirmations** | 12 | 3 | **1**（出块 ~400ms） |
| **HD 内建** | web3j-crypto 有 BIP-32 | bitcoinj 有 DeterministicKey | ✅ solanaj 有 BIP-39/BIP-44 |

## 支持矩阵

| | 钱包创建 | 查余额 | 查链高 | 提现签名+广播 | 扫链 |
|---|---|---|---|---|---|
| **EVM** | ✅ | ✅ | ✅ | ✅ | ✅（主循环完整） |
| **BTC** | ✅ | ✅ | ✅ | ✅（UTXO 选输入） | ❌ 扫链模块留待补 |
| **Solana** | ✅ | ✅ | ✅ | ❌ 抛 UnsupportedOperationException | ❌ |

## 扫链模块

### 职责边界
扫描器**只负责发现链上发生了什么**，不碰私钥、不做账务决策。扫到充值 → 存 DepositEvent(PENDING) → 发 Spring Event → ConfirmationManager 跟踪确认数 → CONFIRMED 再 publishConfirmed，下游账务系统消费后决定入账/冻结/审核。

### 组件
| 组件 | 作用 |
|---|---|
| DepositScanner | EVM 主循环，10s tick |
| BtcDepositScanner | BTC 主循环，30s tick（出块 ~10min） |
| BlockFetcher / BtcBlockFetcher | 拉区块（web3j / blockstream REST） |
| TransactionParser | 解析每笔交易的 ETH/BTC 原生转账 |
| AddressMatcher | 收款地址匹配我们钱包（DB 查询） |
| ReorgHandler | parentHash 链比对，检测到重组自动回滚 |
| ConfirmationManager | latestBlock - deposit.blockNumber 算确认数，达标 → CONFIRMED |
| DepositEventPublisher | Spring ApplicationEvent 输出（可换 Kafka/RabbitMQ） |

### 配置
```yaml
wallet:
  scanner:
    enabled: true            # EVM 扫链开关
    tick-interval-ms: 10000  # EVM tick 间隔
    safe-confirmations: 12   # EVM 安全确认阈值
    start-from-block: -1     # -1=实时从链头，>=0=从历史块补扫
    btc:
      enabled: false         # BTC 扫链默认关
      tick-interval-ms: 30000
      safe-confirmations: 3  # BTC 保守 3 个 ≈ 30min
```

## 配置表

| 配置项 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `wallet.enc-secret` | `WALLET_ENC_SECRET` | `default-32-byte-secret-key-must-be` | AES-GCM 密钥派生密码（**生产必须换**） |
| `wallet.chain.evm-rpc-url` | `EVM_RPC_URL` | sepolia.infura.io | EVM RPC 端点 |
| `wallet.chain.evm-chain-id` | `EVM_CHAIN_ID` | 11155111 | EVM 链 ID（1=mainnet, 11155111=sepolia） |
| `wallet.chain.btc-network` | `BTC_NETWORK` | mainnet | BTC 网络（mainnet / testnet） |
| `wallet.chain.btc-blockstream-url` | `BTC_BLOCKSTREAM_URL` | blockstream.info/api | blockstream 端点 |
| `wallet.chain.solana-rpc-url` | `SOLANA_RPC_URL` | api.devnet.solana.com | Solana RPC 端点 |
| `wallet.scanner.enabled` | — | true | EVM 扫链开关 |
| `wallet.scanner.btc.enabled` | — | false | BTC 扫链开关 |

## 注意事项
- **非 HD 架构是设计选择**：交易所托管钱包不应该有 master key（单点故障风险），每条钱包独立密钥对加密存库，风险水平分散
- **Solana 私钥 64 字节**（seed32 + pubkey32），比 EVM/BTC 的 32 字节长，注意 DB 字段长度
- **BTC 每次提现都是 UTXO 全量拉取**，余额大钱包建议接 UTXO 缓存
- **手续费估算固定 20 sat/vB**，生产应接 `/fee-estimates` 动态费率
- **扫链只解析原生转账**，ERC20/721 Transfer 事件、BTC P2SH/P2MS 多签、Solana 扫链留后续版本
- **重连 H2 Console**：JDBC URL 不变 `jdbc:h2:file:./data/wallet`，重启后数据保留（不再是 mem）
