# auto-karaoke：Android Auto 卡拉 OK（POC）

在 Android Auto 的「正在播放」畫面上即時顯示歌詞：**標題＝目前這句、副標題＝下一句、演出者＝歌名**。
只供個人側載使用，不上架。手機端沒有任何畫面（No Activity），全部從 Android Auto 操作。

> ⚠️ 開車時請勿閱讀歌詞。這是給停車、副駕或 DHU 模擬器測試用的。

## 架構

| 檔案 | 職責 |
|---|---|
| [LrcParser.kt](app/src/main/java/com/joechiboo/autokaraoke/LrcParser.kt) | 純 Kotlin 的 LRC 解析：`[mm:ss]`／`.x`／`.xx`／`.xxx`、一行多個時間標籤、`[ti:]` `[ar:]` `[offset:]` |
| [LyricSyncer.kt](app/src/main/java/com/joechiboo/autokaraoke/LyricSyncer.kt) | 播放中每 200ms 依位置二分搜尋目前句；句子換了才回呼，拖曳後可 `syncNow()` 立即同步 |
| [SongLibrary.kt](app/src/main/java/com/joechiboo/autokaraoke/SongLibrary.kt) | 掃描 `assets/songs/`，建歌曲清單與 Android Auto 瀏覽樹 |
| [KaraokeService.kt](app/src/main/java/com/joechiboo/autokaraoke/KaraokeService.kt) | `MediaLibraryService` 主體：ExoPlayer、瀏覽樹 callback、把歌詞寫進 MediaMetadata |

歌詞更新方式：句子變了就用 `player.replaceMediaItem()` 換掉目前這首的 MediaMetadata。URI 沒變時 ExoPlayer 只更新 metadata、不重新載入音訊，MediaSession 會把新的 metadata 推給 Android Auto。

技術版本：Kotlin（AGP 9.1 內建）、minSdk 26、compileSdk／targetSdk 37、media3 1.11.1。

## 放歌

把 mp3 跟同名的 .lrc 放在 [app/src/main/assets/songs/](app/src/main/assets/songs/)：

```
app/src/main/assets/songs/
├── sample.mp3      ← 自己放
├── sample.lrc      ← 已附範例（自編測試歌詞，約 70 秒）
├── 我的歌.mp3
└── 我的歌.lrc
```

- 有 mp3 才會出現在清單；沒有 .lrc 就只播歌、不顯示歌詞
- 清單上的歌名取自 LRC 的 `[ti:]`，沒有就用檔名
- LRC 請存成 **UTF-8**
- `*.mp3` 已列在 `.gitignore`，不會被推上 GitHub
- 加歌後要重新編譯、重新安裝

### 沒有歌也能測：產生測試音檔

附的兩份 LRC（`sample.lrc`、`sample2.lrc`）可以搭配 ffmpeg 產生的嗶聲音檔。嗶聲落在換句的時間點，聽得出歌詞同步準不準：

```bash
cd app/src/main/assets/songs
# sample：72 秒，每 4 秒嗶一聲（對齊 sample.lrc）
ffmpeg -f lavfi -i "aevalsrc='0.35*sin(2*PI*880*t)*lt(mod(t\,4)\,0.12)+0.12*sin(2*PI*(220+15*floor(t/4))*t)':s=44100:d=72" -ac 1 -b:a 96k sample.mp3
# sample2：30 秒，每 3 秒嗶一聲
ffmpeg -f lavfi -i "aevalsrc='0.35*sin(2*PI*660*t)*lt(mod(t\,3)\,0.12)+0.12*sin(2*PI*(330+20*floor(t/3))*t)':s=44100:d=30" -ac 1 -b:a 96k sample2.mp3
```

## 編譯

需要 JDK 17 以上；PATH 上的 java 太舊的話，指到 Android Studio 內附的 JBR：

```bash
# Git Bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew testDebugUnitTest   # 單元測試（LrcParser、LyricSyncer）
./gradlew assembleDebug       # 產出 app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest   # 實機端對端測試（要接手機、要有上面兩個測試音檔）
```

實機測試（[KaraokeServiceTest.kt](app/src/androidTest/java/com/joechiboo/autokaraoke/KaraokeServiceTest.kt)）會直接連到 KaraokeService，依序檢查瀏覽樹、前奏、自然換句、拖曳、間奏、一行多個時間標籤、暫停、上一首／下一首。另外會用 Android Auto 走的舊版 `MediaBrowserService` 介面，確認 `DISPLAY_TITLE`／`DISPLAY_SUBTITLE` 有正確送出。測試期間手機喇叭會發出嗶聲。

> 不要用 `adb shell cmd media_session dispatch play` 來測：系統會把媒體鍵派給最後播放的 App（通常是 YouTube／YouTube Music），不是這支。

`local.properties` 不在版控內，clone 下來第一次編譯前要建一個：

```
sdk.dir=C:/Users/<你>/AppData/Local/Android/Sdk
```

## 側載到手機

1. 手機開啟開發人員選項：**設定 → 關於手機 → 軟體資訊 → 版本號碼** 連點 7 下
2. **開發人員選項 → USB 偵錯** 打開，接上 USB，手機上按允許
3. 安裝：

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   或直接 `./gradlew installDebug`。

   App 沒有圖示也沒有畫面，桌面上找不到是正常的；在 **設定 → 應用程式** 裡看得到「車上卡拉OK」。

## 開啟 Android Auto 開發者模式與「未知來源」

Android Auto 預設只顯示從 Play 商店安裝的媒體 App，側載的要先開「未知來源」：

1. 打開 Android Auto 設定
   - Android 10 以上內建在系統裡：**設定 → 已連結的裝置 → 連線偏好設定 → Android Auto**（三星：**設定 → 已連接的裝置 → Android Auto**；找不到就在設定裡搜尋「Android Auto」）
2. 捲到最下面，**連點「版本」約 10 下**，出現對話框按確定，就開啟了開發人員設定
3. 右上角 ⋮ → **開發人員設定**
4. 勾選 **「未知來源」（Unknown sources）**
5. 回到 Android Auto 設定 → **自訂啟動器**（Customize launcher），確認「車上卡拉OK」有勾選

## 用 DHU 在電腦上測試

DHU（Desktop Head Unit）是在電腦上模擬車機螢幕的工具，不用上車就能測。

### 1. 安裝 DHU

Android Studio → **Tools → SDK Manager → SDK Tools** 分頁 → 勾 **Android Auto Desktop Head Unit Emulator** → Apply。
裝好後在 `%LOCALAPPDATA%\Android\Sdk\extras\google\auto\desktop-head-unit.exe`。

SDK Manager 跑不動的話（例如 cmdline-tools 沒裝完整），也可以直接下載解壓縮：

```bash
D="$LOCALAPPDATA/Android/Sdk/extras/google/auto"; mkdir -p "$D" && cd "$D"
curl -Lo dhu.zip https://dl.google.com/android/repository/desktop-head-unit-windows-x64_r02.1.zip && unzip -o dhu.zip && rm dhu.zip
```

### 2. 手機啟動 head unit server

Android Auto 設定 → ⋮ → **啟動車用運算主機伺服器**（Start head unit server）。通知列會出現「車用運算主機伺服器執行中」。

### 3. 轉發連接埠並啟動 DHU

手機 USB 接著電腦：

```bash
adb forward tcp:5277 tcp:5277
"$LOCALAPPDATA/Android/Sdk/extras/google/auto/desktop-head-unit.exe"
```

DHU 視窗開起來後，點左下角的應用程式格 → **車上卡拉OK** → 歌曲 → 點一首開始播放。

### 4. 要測的項目

| 項目 | 預期 |
|---|---|
| 瀏覽 | 「歌曲」分頁列出 assets 裡所有 mp3；沒放 mp3 時顯示「還沒有歌曲」提示 |
| 前奏 | 標題顯示「♪ 歌名」，副標題顯示第一句 |
| 歌詞同步 | 每到 LRC 時間點，標題換成目前句、副標題換成下一句；演出者一直是歌名 |
| 拖曳進度 | 放開後歌詞立即跳到對應的句子（不用等 200ms） |
| 上一首／下一首 | 切到清單中前後一首，歌詞跟著換 |
| 暫停 | 停在目前這句；繼續播放後接著同步 |

DHU 常用操作：`dayNight` 切換日夜模式、視窗大小可在 DHU 設定檔調整。

## 疑難排解

- **Android Auto 裡看不到 App**：確認「未知來源」已勾、自訂啟動器有勾選；裝完 App 後把 Android Auto（或 DHU）完全關掉重開
- **看 log**：`adb logcat | grep -iE "karaoke|MediaSession|ExoPlayer"`
- **確認 Session 有在跑**：播放時 `adb shell dumpsys media_session`，應該看到 `com.joechiboo.autokaraoke` 與目前的 metadata
- **歌詞亂碼**：LRC 不是 UTF-8，用編輯器另存成 UTF-8

## 已知限制（POC）

- Android Auto 的佇列（Queue）畫面裡，正在播放那首的標題也會跟著變成目前這句歌詞
- 標題太長時 Android Auto 會截斷；長句建議在 LRC 裡自己斷成兩句
- 歌曲是打包在 APK 裡，換歌要重新編譯
