# 文件分割合并 · Android 版

把 Windows 小工具「文件分割 - zip2.0.exe」完整搬到 Android 上。
**原工具的全部功能都已保留**，并可端到端验证（分割 → 合并 → MD5 一致）。

---

## 1. 原工具是什么（逆向结果）

`文件分割 - zip2.0.exe` 是 **PyInstaller 打包的 Python 3.6 程序**（6.2 MB，内含 `python36.dll`）。
解包后主脚本 `文件分割 - zip2.0.py` 被完整还原，逻辑如下：

```python
import sys, os, random

def split_file(file_path, min_size=83886080, max_size=94371840):
    with open(file_path, 'rb') as f:
        n = 1
        while True:
            remain = os.path.getsize(file_path) - f.tell()
            size = min(random.randint(min_size, max_size), remain)
            chunk = f.read(size)
            if not chunk: break
            with open(f"{file_path}_{n}.zip", 'wb') as cf: cf.write(chunk)
            n += 1

def merge_files(prefix, num_parts):
    buf = bytearray()
    for i in range(1, num_parts + 1):
        with open(f"{prefix}_{i}.zip", 'rb') as f: buf.extend(f.read())
    with open(prefix, 'wb') as o: o.write(buf)

while True:
    mode = input('请输入模式,1.分割文件;2.合并文件;3.退出>')
    ...
```

关键行为：

| 行为 | 说明 |
|---|---|
| 分片大小 | **每次随机**取 `[80 MB, 90 MB]`，再与剩余大小取小值 |
| 分片命名 | `源文件名_1.zip`、`源文件名_2.zip` …（**`.zip` 只是伪装**，不做任何压缩，用于绕过网盘后缀校验） |
| 分片位置 | 与源文件同目录 |
| 合并输入 | 不含 `_序号` 的完整路径前缀 |
| 分片探测 | 从 `_1.zip` 开始连续 open，失败即停 → 得到片数 |
| 合并实现 | 全部读进 `bytearray` 再写出（大文件会吃内存） |
| 菜单 | `1.分割 / 2.合并 / 3.退出` |

> 为什么随机大小？蓝奏云等网盘对"等分大文件"有识别，随机化能降低被判定为分卷的概率。

## 2. Android 版做了什么

包名 `com.dsh.zipsplit`，最低 Android 7.0（minSdk 24），targetSdk 30。

### 功能对照

| 原工具 | Android 版 | 状态 |
|---|---|---|
| 模式 1：分割文件 | 「分割文件」标签页 | ✅ 一致 |
| 输入目标文件路径 | 路径输入框 + **内置文件浏览器** | ✅ 增强 |
| 分片 80~90 MB 随机 | 默认 80~90，**可调** | ✅ 保留默认+可调 |
| 输出 `原名_N.zip` | 完全一致 | ✅ |
| 分片存放 | 同目录下的 **`原名.parts/` 文件夹** | 🔧 按需求调整 |
| 选分片方式 | 选**任意文件**，自动检测同目录全部切片 | 🔧 按需求调整 |
| 模式 2：合并文件 | 「合并文件」标签页 | ✅ |
| 输入不含 `_n` 的前缀 | 输入前缀，或**直接选任一分片自动推导** | ✅ 增强 |
| 自动探测分片数量 | 一致（并显示合计大小与还原目标） | ✅ |
| 错误提示 `发生了错误:` | 状态栏 ✗ 文案 | ✅ |
| 成功提示 `成功生成至同文件夹下!!!` | 状态栏 ✓ 文案 + **耗时** | ✅ |
| 模式 3：退出 | 返回键 | ✅ |
| 全程内存缓冲 | 改为 **1 MB 流式复制**（几 GB 文件也不 OOM） | ✅ 增强 |
| — | 进度条、可取消、大文件友好 | ✨ 新增 |

### 合并入口与分片文件夹（v1.2）

分割 `movie.mkv` 会在同目录建立 **`movie.mkv.parts/`**，切片 `movie.mkv_1.zip`、`movie.mkv_2.zip` … 全部放进去。
> 说明：同一目录下不允许同时存在同名文件和文件夹，所以用了 `.parts` 后缀（改一个常量即可换名）。

合并页只有一个入口：**选任意一个文件**，程序自动检测它所在目录里的全部切片（成组且连续）。覆盖的情形：

| 你选的东西 | 行为 |
|---|---|
| `movie.mkv.parts/movie.mkv_3.zip`（某个切片） | 按该前缀定位整组，还原为 `movie.mkv`（落在外层） |
| `movie.mkv`（原文件，**即使已被删除**） | 同目录找不到切片时自动进入同名 `movie.mkv.parts/`，还原到外层 |
| 散放在同一目录里的任一切片 | 按前缀定位整组，还原就在该目录内 |
| 同目录其它任意文件 | 自动扫描，取片数最多的一组切片（兼容旧版散放分片） |

**顺序保证**：切片名的序号就是合并顺序。前提是文件名不被改动、切片齐全——每片大小是随机 80~90MB，无法靠体积判断先后。

### 合并后要不要删切片（v1.3）

合并页有「合并成功后删除切片文件」复选框，**默认不勾选**：

| 勾选状态 | 行为 |
|---|---|
| 不勾选（默认） | 还原完成后切片原样保留，方便再合并一次或留作备份 |
| 勾选 | 仅在**还原成功之后**才删除该组切片；若切片位于 `xxx.parts/` 内且被删空，空文件夹一并删掉 |

安全边界：只删 `<原名>_N.zip` 这些切片文件，**绝不会碰还原出来的目标文件**；合并失败或被取消时一个都不删。

### 安全细节（相对原工具的改进）

- 合并先写 `xxx.merging` 临时文件，成功后再原子重命名覆盖 —— 中途断电不会毁掉已有文件。
- 分割前若同目录已有同名分片，会提示"将被覆盖"。
- 选中本身像 `xxx_1.zip` 的文件去分割时，会二次确认，避免误操作把分片再切一遍。

## 3. 安装

APK 已经装到本机，副本在：

```
/storage/emulated/0/Download/文件分割合并-v1.0.apk
```

首次启动会引导开启「所有文件访问」权限（`MANAGE_EXTERNAL_STORAGE`）。
如果没有弹窗，可手动到 **系统设置 → 应用 → 文件分割合并 → 权限** 打开。

非 root 安装：

```sh
adb install -r "文件分割合并-v1.0.apk"
```

本机（有 root）安装：

```sh
su -c "cp 文件分割合并-v1.0.apk /data/local/tmp/ && pm install -r -t /data/local/tmp/zipsplit.apk"
su -c "appops set com.dsh.zipsplit MANAGE_EXTERNAL_STORAGE allow"
```

## 4. 目录结构

```
/storage/emulated/0/DSH/zip2apk/
├── README.md                 本文档
├── app/
│   ├── AndroidManifest.xml   可读源 Manifest（编译成二进制后进 APK）
│   ├── src/com/dsh/zipsplit/
│   │   ├── MainActivity.java 全部 UI（纯代码构建，零 XML 布局/零图片资源依赖）
│   │   └── Engine.java       分割/合并核心算法（流式）
│   ├── res/mipmap-hdpi/      图标 PNG（由脚本生成）
│   └── out/                  构建产物
│       └── zipsplit.apk      ★ 签名后的成品
├── tools/
│   ├── axml.py               二进制 AndroidManifest.xml 生成/解析
│   ├── resgen.py             图标 PNG + 最小 resources.arsc 生成
│   ├── pack.py               打包 + zipalign（4 字节对齐）
│   ├── build.sh              一键构建
│   ├── py36marshal.py        Python 3.6 marshal 读取器（逆向用）
│   └── disasm36.py           Python 3.6 字节码反汇编器（逆向用）
└── work/                     逆向过程产物（提取的 exe 内容、反汇编等）
```

## 5. 重新构建（完全离线，零下载）

本机已具备所需的全部工具链，**不需要联网下载任何东西**：

- JDK 17：`/data/data/com.dsharnessmobile.shell/files/opt/jdk17/.../bin`
- `android.jar`(API 30)、`r8`(D8)、`apksigner.jar`：来自 `../xhs-mod/`

```sh
sh /storage/emulated/0/DSH/zip2apk/tools/build.sh
```

产物：`app/out/zipsplit.apk`（自签名，密钥 `app/apk/zipsplit.jks`，口令 `123456`）。

## 6. 技术要点（为什么没有 aapt2 也能构建）

Android SDK 的 `aapt2` 是 x86-64 二进制，跑不动 ARM 设备；Termux 里也没有 aapt。
因此本项目**自己实现了构建链里 aapt2 的那部分**：

1. **二进制 AndroidManifest.xml**（`tools/axml.py`）
   按 AOSP 的 `ResXMLTree` 结构手写：`RES_XML_TYPE` 容器 + StringPool + ResourceMap + Namespace + Element/Attribute。
   资源 ID 全部从平台 `android.jar` 里实测取得（`tools/DumpIds.java`），保证 `android:name`/`icon`/`label` 等属性 ID 与 PackageParser 期望一致。

2. **最小 resources.arsc**（`tools/resgen.py`）
   只含一个 `mipmap/ic_launcher`（和一个 `string/app_name`），结构为
   `RES_TABLE → 全局字符串池 → RES_TABLE_PACKAGE → 类型字符串池 / 键字符串池 / TypeSpec / Type`。

3. **zipalign**（`tools/pack.py`）
   通过 zip "extra" 字段补齐，让每个条目数据偏移 4 字节对齐 —— `resources.arsc` 不对齐会导致 AssetManager 无法 mmap，资源整体加载失败。

4. **图标**（`tools/resgen.py`）
   纯 Python 写 PNG（zlib + CRC），4× 超采样抗锯齿绘制。

### 踩过的两个坑（值得记下来）

- **UTF-8 字符串池的字符串有"两个"长度前缀**：先 AOSP 的 UTF-16 字符数，再 UTF-8 字节数。
  只写一个长度时，Android 报 `Bad string block: string #1 extends to 26298, past end at 56`，
  安装直接失败（`INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION`）。
  同理，apksigner 解析 AXML 时也报 `UTF-8 encoded form of string not NULL terminated`。
- **resources.arsc 必须 4 字节对齐**，否则资源静默失效（表现为应用图标回退成系统默认图标，但安装不报错）。

## 7. 验证记录

| 用例 | 结果 |
|---|---|
| 5 MiB 文件 → 1~2 MB 分片 | 生成 3 片，合计等于原文件 ✅ |
| 5 MiB 合并 | MD5 `1dbf3dd9…34ec` 一致 ✅ |
| 250 MiB 文件 → 80~90 MB 分片 | 生成 4 片（84.1/90.9/86.0/1.0 MB），合计 262 144 000 B ✅ |
| 250 MiB 合并 | MD5 `680bf4a0…cec3` 一致 ✅ |
| 文件浏览器 / 选分片自动推导前缀 | ✅ |
| 应用图标 | 系统「应用信息」页正确显示自绘图标 ✅ |
| 3 MiB → 分割到 `sample.bin.parts/` | 切片全部落在文件夹内，合计精确等于原文件 ✅ |
| 选外层 `sample.bin`（**文件已删除**） | 自动进同名 `.parts/` 找到 2 片，MD5 `a15a4b7b…7e85` 一致 ✅ |
| 进 `.parts/` 选 `sample.bin_1.zip` | 自动检测整组、还原到外层，MD5 一致 ✅ |
| 长按浏览按钮 | 已无文件夹选择模式（「选择此文件夹」按钮已移除）✅ |
| 勾选「删除切片」后合并 | 还原 MD5 `1fae243b…be3c` 一致，2 个切片与空的 .parts 文件夹均被清理 ✅ |
| 不勾选时合并（默认） | 还原 MD5 一致，切片与 .parts 文件夹原样保留 ✅ |
| 权限流程 | 「所有文件访问」引导 ✅ |
