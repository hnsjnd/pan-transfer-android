# 网盘转存（Android）

包名：`com.shicheng.pantransfer`  
显示名：网盘转存  
版本：1.0.0

帮助你从百度 / 阿里云盘 / 夸克（及 UC）分享文案中识别链接与提取码，保存历史，并一键唤起官方 App 或复制「助手转存指令」。

## 安装到手机（侧载 Debug APK）

1. 将 `dist/网盘转存.apk` 传到手机（微信/电脑/数据线均可）。
2. 打开 APK。若系统提示「禁止未知来源」，到 **设置 → 安全 / 应用** 中允许当前安装来源（如文件管理器、浏览器）。
3. 安装完成后打开「网盘转存」。
4. 本包为 **debug 签名**，可直接侧载；卸载后重装不会保留本地历史（历史存在 App 内 localStorage）。

## 使用

1. 粘贴分享文本（含链接与提取码），点 **识别解析**；也可从其他 App **分享到本 App**。
2. 选择默认目标盘（阿里云盘 / 百度 / 夸克），用于生成助手指令。
3. 可 **打开链接**（唤起浏览器或已装网盘 App）、**复制链接 / 提取码**、复制 **助手转存指令**。
4. 历史约保留最近 50 条，可单条删除或清空。

## 本版限制（请知悉）

- **同盘转存最稳**；跨盘官方不支持直接秒传。
- v1 只做 **识别、历史、唤起与复制**，**不做**自动登录、Cookie/Token 采集或非官方秒传接口。
- 打开链接依赖系统 Intent；未安装对应网盘 App 时会用浏览器打开。
- 剪贴板「读取」在部分 WebView 环境受限；可用长按粘贴，或从其他 App 分享进来。

## 后续可接（自动转存）

- 官方 OAuth / 开放平台授权（优先）。
- 用户自建中转服务（用户自持 Token，App 只发链接与提取码）。
- 同盘「转存到自己的盘」自动化；跨盘仍需下载再上传或官方中转能力。

## 开发构建

环境：JDK 17、Android SDK（`ANDROID_HOME`/`ANDROID_SDK_ROOT`）、本仓库 Gradle Wrapper。

```bash
export JAVA_HOME=/workspace/jdk-17   # 或本机 JDK 17+
export ANDROID_HOME=/home/box/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
cd /workspace/pan-transfer-android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/网盘转存-1.0.0-debug.apk
```

## 实现说明

- 原生壳：单 Activity + WebView，加载 `assets/index.html`。
- 识别与历史：纯前端；偏好与历史存 `localStorage`。
- 打开外链 / 复制：经 `AndroidBridge` JS 接口调用系统 Intent 与剪贴板。
