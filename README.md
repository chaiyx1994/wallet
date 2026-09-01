# wallet
多链托管钱包后端项目（学习版本），支持 EVM / Solana / BTC 三条链的钱包创建。

## 技术栈
- Java 21 + Spring Boot 2.7
- Spring Data JPA + H2 内存数据库
- web3j（EVM 密钥生成 & 地址派生）
- solanaj（Solana 密钥生成）
- bitcoinj（BTC 密钥生成 & 地址派生）
- AES-256-GCM 私钥加密存储

## 项目结构
```
walletgit/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/wallet/
    │   ├── WalletApplication.java      # 启动类
    │   ├── config/
    │   │   └── GlobalExceptionHandler.java
    │   ├── controller/
    │   │   └── WalletController.java   # REST API
    │   ├── crypto/
    │   │   └── AesGcmEncryptor.java    # 私钥 AES-GCM 加密
    │   ├── dto/
    │   │   ├── ApiResponse.java
    │   │   └── CreateWalletRequest.java
    │   ├── entity/
    │   │   └── Wallet.java             # JPA 实体
    │   ├── enums/
    │   │   ├── ChainType.java
    │   │   └── WalletType.java
    │   ├── repository/
    │   │   └── WalletRepository.java
    │   ├── service/
    │   │   └── WalletService.java      # 创建钱包核心逻辑
    │   └── adapter/
    │       ├── ChainAdapter.java       # 多链适配接口
    │       ├── ChainAdapterFactory.java
    │       └── impl/
    │           ├── EvmMemoryAdapter.java
    │           ├── SolanaMemoryAdapter.java
    │           └── BtcMemoryAdapter.java
    └── resources/
        └── application.yml
```

## 核心架构
```
WalletController  →  WalletService  →  AesGcmEncryptor (加密私钥)
                                          ↓
                              WalletRepository → H2 Database
                                          ↓
ChainAdapterFactory → ChainAdapter (EVM/SOL/BTC 查询余额)
```

## 创建钱包的流程
1. `POST /api/wallets` 传入 `{ chainType, walletType }`
2. `WalletService` 根据链类型分支：
   - **EVM**: web3j `Keys.createEcKeyPair()` → 私钥/公钥/地址
   - **Solana**: solanaj `new Account()` → base58 私钥/公钥/地址
   - **BTC**: bitcoinj `new ECKey()` → P2PKH 地址（testnet）
3. 明文私钥 → `AesGcmEncryptor.encrypt()` (AES-256-GCM) → 密文
4. `Wallet` 实体持久化到 H2，**只存加密后的私钥，明文不落盘**

## 快速开始
```bash
cd walletgit
mvn spring-boot:run
```

服务启动后访问：
- H2 Console: http://localhost:8080/h2-console (JDBC URL: `jdbc:h2:mem:wallet`)

### 创建钱包
```bash
# EVM
curl -X POST http://localhost:8080/api/wallets \
  -H 'Content-Type: application/json' \
  -d '{"chainType":"EVM","walletType":"HOT"}'

# Solana
curl -X POST http://localhost:8080/api/wallets \
  -H 'Content-Type: application/json' \
  -d '{"chainType":"SOLANA","walletType":"HOT"}'

# BTC
curl -X POST http://localhost:8080/api/wallets \
  -H 'Content-Type: application/json' \
  -d '{"chainType":"BTC","walletType":"HOT"}'
```

### 查询
```bash
# 列出所有钱包
curl http://localhost:8080/api/wallets

# 按链类型筛选
curl http://localhost:8080/api/wallets?chainType=EVM

# 查余额（Memory 适配器返回 0）
curl http://localhost:8080/api/wallets/1/balance
```

## 配置
| 配置项 | 环境变量 | 默认值 | 说明 |
|-------|---------|-------|------|
| `wallet.enc-secret` | `WALLET_ENC_SECRET` | `default-32-byte-secret-key-must-be` | AES-GCM 密钥派生密码（生产环境必须替换） |

