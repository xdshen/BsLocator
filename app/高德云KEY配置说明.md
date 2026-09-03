# 高德地图 API Key 配置指南

## 为什么需要配置 Key？

高德地图 SDK 需要每个应用配置唯一的 API Key 才能正常使用地图服务。

## 获取 Key 的步骤

### 1. 注册高德开发者账号
访问 [高德开放平台](https://lbs.amap.com/)，注册并登录。

### 2. 创建应用
1. 进入 [控制台 → 应用管理 → 我的应用](https://console.amap.com/dev/key/app)
2. 点击「创建新应用」
3. 应用名称：`BsLocator`
4. 应用类型：选择「出行」或「工具」

### 3. 添加 Android Key
1. 在应用下点击「添加」→「Android平台」
2. 填写信息：
   - **PackageName**：`com.example.bslocator`
   - **SHA1**：需要获取你的开发环境的 SHA1 指纹

### 4. 获取 SHA1 指纹

#### Debug 模式（开发测试）
打开终端，执行：
```bash
# macOS / Linux
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android

# Windows (Git Bash / CMD)
keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android
```

从输出中找到 `SHA1` 字段的值，类似：
```
SHA1: A1:B2:C3:D4:E5:F6:... （40位十六进制字符串）
```

#### Release 模式（正式发布）
使用你的发布签名证书的 SHA1：
```bash
keytool -list -v -keystore your-release-key.keystore
```

### 5. 将 Key 填入项目

打开 `app/src/main/AndroidManifest.xml`，找到这一行：
```xml
<meta-data
    android:name="com.amap.api.v2.apikey"
    android:value="你的高德Key请替换此处" />
```

将 `android:value` 替换为你在高德控制台获取的真实 Key。

### 6. 同步并运行

在 Android Studio 中点击 **Sync Project with Gradle Files**，然后运行到真机。

---

## 常见问题

### Q: 地图显示白屏/只有网格？
A: 99% 是 API Key 配置错误。请检查：
- Key 是否已替换（不是占位符）
- 包名是否完全匹配 `com.example.bslocator`
- SHA1 是否与当前使用的签名证书一致
- 是否在高德控制台启用了「地图SDK」服务

### Q: 定位蓝点不显示？
A: 请确保已在 Android 设置中授予 APP「位置信息」权限，并且 GPS 已开启。

### Q: 需要付费吗？
A: 高德地图个人开发者有免费额度（日请求量限制），路测场景通常不会超出。如需更高配额，可在控制台申请企业认证。
