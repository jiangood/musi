# Literal Musi 用户指南

Literal Musi 是一款受 howm 启发的极简音乐播放器——理念是"先听，永远不整理"。

## 快速开始

1. 将音乐文件复制到 `pile/` 目录（或使用 Git 同步）
2. 打开应用
3. 点击曲目开始播放

## 搜索

在搜索栏中输入内容。结果会按文件名显示匹配的曲目。

## 删除

在任何曲目上向左滑动即可删除。

已删除的曲目会被移动到远程仓库中的 `trash/` 目录。它们会立即从应用中消失。

没有本地回收站。没有恢复按钮。这是有意为之——删除应该有终结感，保持 pile 的整洁。

如果你真的需要找回什么，在仓库的 `trash/` 中找到它，然后复制回 `pile/`。

## GitHub 同步

### 设置

1. 创建一个 GitHub 仓库（建议设为私有）
2. 生成 Personal Access Token：
   - 访问 github.com > Settings > Developer settings > Personal access tokens > Tokens (classic)
   - 点击 "Generate new token (classic)"
   - 命名为 "Literal Musi"
   - 选择 "repo" 范围（完全控制私有仓库）
   - 点击 "Generate token"
   - 立即复制 token
3. 在应用中进入 设置 > 连接 GitHub
4. 粘贴 token 并输入仓库名称（例如 `username/music`）

### 多设备同步

- 启动应用时自动同步
- 不要在多台设备上同时修改同一个文件
- 如果发生冲突，先同步的设备胜出
- 所有变更都会保留在 Git 历史中

### 仓库结构

```
repo/
├── pile/    ← 活跃音乐文件
└── trash/   ← 已删除文件（仅 Git 同步，用于恢复）
```

### PC 使用

直接从终端管理曲目：

```bash
git pull
# 添加音乐文件到 pile/
git add . && git commit -m "添加新曲目" && git push
```

永久删除：

```bash
rm trash/old_track.mp3
git add . && git commit -m "清理" && git push
```

## 支持的格式

- MP3 (.mp3)
- FLAC (.flac)
- OGG (.ogg)
- Opus (.opus)
- WAV (.wav)
- M4A / AAC (.m4a, .aac)

## 提示

- 文件命名要清晰。文件名就是你要搜索的内容。
- 在 `pile/` 中使用文件夹作为专辑。
- 在多台设备上经常同步。
- 不要整理。搜索。
