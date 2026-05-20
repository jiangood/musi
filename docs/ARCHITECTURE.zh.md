# Literal Musi – 架构设计

## 概述

Literal Musi 是一个极简的 Android 本地音乐播放器。没有歌单、没有元数据抓取、没有算法推荐——只有 `pile/` 和 `trash/` 中的音频文件，通过 Git 同步。

## 技术栈

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Hilt** (DI) + **KSP** (注解处理)
- **ExoPlayer** (音频播放)
- **DataStore Preferences** (设置) + **EncryptedSharedPreferences** (GitHub token)
- **目标**: Android 8.0+ (API 26), compileSdk/targetSdk 35
- **无 Google API、无 Firebase、无追踪**

## 核心架构

| 路径 | 职责 |
|---|---|
| `app/src/main/java/fumi/day/literalmusi/` | 所有源代码 |
| `data/repository/MusicRepositoryImpl.kt` | 基于文件的音乐 CRUD（读写 `filesDir/pile/`） |
| `data/player/MusicPlayer.kt` | 基于 ExoPlayer 的音频播放封装 |
| `data/github/GitHubSyncManager.kt` | 双向同步逻辑（本地 ↔ 远程） |
| `data/github/GitHubRepository.kt` | 原始 GitHub REST API 调用 |
| `data/git/GitForge.kt` | Forge API 接口抽象 |
| `ui/navigation/NavGraph.kt` | 路由：列表 → 播放器，设置 |
| `ui/list/MusicListScreen.kt` | 音乐列表 + 搜索 + 滑动删除 |
| `ui/player/PlayerScreen.kt` | 正在播放界面，带播放控制 |
| `ui/settings/SettingsScreen.kt` | Git 配置、主题、音频设置 |
| `data/prefs/UserPreferences.kt` | DataStore + EncryptedSharedPrefs 设置 |

## 数据流

```
用户点击文件
  → MusicListScreen
  → MusicRepository（解析文件路径）
  → MusicPlayer（启动 ExoPlayer 播放）

搜索
  → MusicListScreen（搜索栏）
  → MusicRepository（按文件名过滤 pile/*）
  → 显示匹配文件

同步
  → 应用启动 / 变更后
  → GitHubSyncManager
  → GitHubRepository（REST API）
  → MusicRepository（更新本地文件）
```

## 目录结构

```
repo/
├── pile/    ← 音乐文件（mp3, flac, opus 等）
└── trash/   ← 已删除文件（仅 Git 同步，用于恢复）
```

没有数据库。没有音乐库索引。文件系统**就是**音乐库。

## 构建与运行

```powershell
# Debug
./gradlew assembleDebug

# Release（需要 local.properties 或环境变量中的签名配置）
./gradlew assembleRelease

# 安装 debug APK
./gradlew installDebug
```

Release 签名：`local.properties` 中的 `STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，或 CI 环境变量 `CI_KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`。

## 约定

- **Kotlin 代码风格**: `kotlin.code.style=official` in gradle.properties
- **文件命名**: 音乐文件在 `pile/` 中保留原始文件名
- **同步**: `onResume()` 时自动同步；冲突时先同步者胜出
- **无备份** (`android:allowBackup="false"`)
- **ProGuard** 在 Release 中启用；保留 Hilt、ViewModel、协程
- **支持格式**: mp3, flac, ogg, opus, wav, m4a, aac

## 重要约束

- 应用命名空间: `fumi.day.literalmusi`
- 最低 SDK 26 → 不能使用需要更高版本的 Java 8+ API（但 `java.time` 可用）
- EncryptedSharedPreferences 仅用于 token 存储；通用设置在 DataStore 中
- 启动时不扫描元数据——文件系统是唯一真相来源
