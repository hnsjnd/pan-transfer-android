# 网盘转存（Android）

包名：`com.shicheng.pantransfer`  
显示名：网盘转存  
版本：**1.2.0**（versionCode 3）

跨盘转存到阿里云盘是核心能力：夸克分享 → 自己的阿里云盘；阿里分享 → 自己的阿里（同盘）。

## 安装

1. 下载 Release 中的 `pan-transfer.apk`（或 `dist/网盘转存.apk`）。
2. 侧载安装；若提示未知来源，在系统设置中允许当前安装来源。
3. 本包为 **debug 签名**。

## 使用前：登录（必做）

Token / Cookie **只保存在手机本地**（EncryptedSharedPreferences），**不会**发到第三方服务器。

### 阿里云盘（目标盘，必登）

任选其一：

1. App 内点 **登录** → 网页扫码/短信登录；成功后自动抓取 `refresh_token`。
2. 或粘贴 `refresh_token`（可从浏览器登录阿里云盘后，在开发者工具 Application → Local Storage 中找到含 `refresh_token` 的 JSON）。

登录后可见昵称；**退出**会清除本地 token。

### 夸克（跨盘来源）

1. 点 **登录** → 在 WebView 打开 `pan.quark.cn` 完成登录。
2. 点 **完成并保存 Cookie**；App 会校验并加密保存。

## 功能

| 场景 | 行为 |
|------|------|
| **阿里分享 → 自己的阿里** | 同盘官方转存（get_share_token → list → copy/batch），保存到根目录文件夹「网盘转存」 |
| **夸克分享 → 自己的阿里** | 无官方秒传：用夸克 Cookie 解析分享 → 转存到自己的夸克临时目录 → 取下载链 → 本机下载 → 上传到阿里「网盘转存」；有进度条 |
| 识别 / 历史 / 分享到 App / 打开官方链接 | 保留 |

### 体积限制（手机端诚实约束）

- **> 500MB**：弹窗确认（流量与耗时警告）
- **> 2GB**：拒绝在手机端处理，请改用电脑

跨盘 **不是秒传**，速度受手机网络与双端限速影响。

## 本版明确不做 / 未完成

- **不宣称跨盘秒传**
- 百度 → 阿里：UI 可识别链接，自动跨盘路径 **未接入**（可用打开链接 / 助手指令）
- 不自选转存目录（固定「网盘转存」）
- 不做演示模式 / 假成功

## 开发构建

```bash
export JAVA_HOME=/workspace/jdk-17
export ANDROID_HOME=/workspace/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
cd /workspace/pan-transfer-android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/网盘转存-1.2.0-debug.apk
# 复制：dist/网盘转存.apk 与 dist/pan-transfer.apk
```

## 实现说明

- 原生壳：`MainActivity` WebView + `AndroidBridge`；`LoginActivity` 负责网页登录 / Cookie 捕获。
- 网络：OkHttp 调用阿里个人盘 API、夸克 Cookie API。
- 存储：`SecureStore`（EncryptedSharedPreferences）。
- 前端：`assets/index.html` 深色 UI；账号区 + 转存进度。

## 仓库

https://github.com/hnsjnd/pan-transfer-android
