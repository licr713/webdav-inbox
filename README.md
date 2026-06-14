# 📥 Inbox Android App

华为手机上用的收件箱 APK。WebView 壳 + 系统分享接收。

## 装什么

这个 App 让你在华为手机上：
- ✅ 桌面图标打开收件箱（WebView 加载 inbox.oolool.com）
- ✅ 任何 App 分享→自动存入 WebDAV（图片/视频/文件/文字）
- ✅ 透明后台接收，不打断操作

## 怎么编译

> 注意：Android 编译工具链只有 x86 架构，我这台 ARM VPS 编不了。
> 你用 Mac 编译只需 30 秒。

**方法一：Mac 本地编译（推荐，最快）**

```bash
# 1. 下载项目
git clone <repo-url> inbox-android
cd inbox-android

# 2. 生成 gradle wrapper（第一次需要装 Java 17）
brew install openjdk@17
export ANDROID_HOME=~/Library/Android/sdk
./gradlew assembleDebug

# 3. APK 在 app/build/outputs/apk/debug/app-debug.apk
# 用数据线传到华为手机，打开文件点安装即可
```

**方法二：GitHub Actions（不需要本地环境）**

1. 把这个项目推到你的 GitHub 仓库
2. Actions 自动编译
3. 去 Actions 页面下载 APK artifact

**方法三：我帮你编（远程）**

如果你授权我操作你的 GitHub 仓库，我直接配好 Actions workflow，你只需要：
1. Fork/push 到 GitHub
2. 等 2 分钟
3. 下载 APK

## 安装到华为手机

1. 把 APK 传到手机（微信文件传输 / USB 数据线 / 华为分享）
2. 打开文件 → 提示"安全风险" → 点"继续安装"
3. 安装完成

> 首次安装非应用市场的 App，华为会提示风险。这是正常的，点"继续安装"即可。
> 需要打开"设置 → 安全 → 更多安全设置 → 允许安装未知来源应用"

## 使用

安装后：
1. 打开 App → 登录 licr713 → 自动记住登录
2. 在任何 App 里点"分享" → 选择"收件箱" → 自动上传
3. 桌面图标直接打开收件箱页面

## 隐私

- App 只需要网络权限（上传文件用）
- 不留本地存储，所有数据直接 POST 到 inbox.oolool.com
- 登录凭据存在 WebView cookie 里
