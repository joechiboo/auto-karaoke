# auto-karaoke：Android Auto 卡拉 OK（POC）

在 Android Auto 的「正在播放」畫面上即時顯示歌詞：**標題＝目前這句、副標題＝下一句**，歌名與歌手印在右邊的封面上。播放畫面右下角有三顆按鈕：**－／＋** 把字幕延後／提早 0.5 秒（每首歌各自記住），**字幕** 鈕可以關掉歌詞當一般音樂播放器用。
只供個人側載使用，不上架。手機端沒有任何畫面（No Activity），全部從 Android Auto 操作。

> ⚠️ 開車時請勿閱讀歌詞。這是給停車、副駕或 DHU 模擬器測試用的。

## 架構

| 檔案 | 職責 |
|---|---|
| [LrcParser.kt](app/src/main/java/com/joechiboo/autokaraoke/LrcParser.kt) | 純 Kotlin 的 LRC 解析：`[mm:ss]`／`.x`／`.xx`／`.xxx`、一行多個時間標籤、`[ti:]` `[ar:]` `[offset:]` |
| [LyricSyncer.kt](app/src/main/java/com/joechiboo/autokaraoke/LyricSyncer.kt) | 播放中每 200ms 依位置二分搜尋目前句；句子換了才回呼，拖曳後可 `syncNow()` 立即同步 |
| [ArtworkFactory.kt](app/src/main/java/com/joechiboo/autokaraoke/ArtworkFactory.kt) | 產生印著歌名＋歌手的封面（有副標題時 Auto 不顯示演出者，歌名只能放封面） |
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
- `*.mp3` 與 `*.lrc` 都列在 `.gitignore`（歌詞也有版權），只有自編的 `sample*.lrc` 進版控
- 加歌後要重新編譯、重新安裝

### 加歌流程

歌單控制在 **20～30 首**（約 250 MB）。歌打包在 APK 裡，再多的話每次重裝都很慢；真的要更多首，就得改成讀手機資料夾。

加一首歌要滿足兩個條件：

1. **Argus 點唱機的快取裡有這首**：在監控牆點過的 YouTube 歌，伺服器會轉成 mp3 存進快取。所以先在牆上點一次，mp3 就準備好了。
   - ⚠️ Argus 快取有容量上限，舊的會被自動清掉。想加的歌點完就盡快抓下來，抓下來之後就跟 Argus 無關了。
2. **LRCLIB 有同步歌詞**：熱門歌幾乎都有，冷門或很舊的中文歌比較可能沒有。

接著跑 [tools/add_song.py](tools/add_song.py)。它會從 Argus 下載 mp3、到 LRCLIB 找同步歌詞，挑長度跟 mp3 最接近的那份：

```bash
echo "ARGUS_URL=http://<argus 主機>:<埠>" > tools/local.env   # 只放本機，不進版控
python -X utf8 tools/add_song.py "Queencard" --artist "(G)I-DLE" --track "Queencard"
#   第一個參數       在 Argus 快取標題裡找的關鍵字（要只對到一首）
#   --lrclib <id>    指定某份歌詞
#   --offset <毫秒>  寫入 [offset:]（正值＝提早）
#   --force          長度差超過門檻也照寫
./gradlew installDebug   # 手機要接著
```

| 腳本結果 | 怎麼辦 |
|---|---|
| 長度差 ≤ 2 秒 | 自動寫檔，直接用 |
| 長度差 > 2 秒 | 不寫檔，只列候選。通常是 MV 版多了前奏：看候選裡有沒有照 MV 版排的歌詞（長度剛好對上）；沒有的話用 `--offset` 補上開頭差距 |
| MV 在歌曲中間有剪接 | 前半首對、後半首偏，整首加減秒數救不回來，換一首 |
| LRCLIB 沒有同步歌詞 | 換一首，或自己寫 LRC |

上車後覺得差個零點幾秒，就用播放畫面的 －／＋ 鈕微調。調整值每首各自記在手機上，不會寫回檔案。

注意：LRCLIB 是外部網站，腳本每次請求固定間隔 2 秒，不要拿來大量查詢。

### 目前歌單

mp3 和 LRC 都不進版控，只存在開發機上。換電腦或檔案不見時，照下表重新抓回來（`--lrclib` 指定當初用的那份歌詞，結果才會一樣）：

| 歌手 - 歌名 | Argus 關鍵字 | LRCLIB | offset | 備註 |
|---|---|---|---|---|
| (G)I-DLE - Queencard | `Queencard` | 6608612 | | LRCLIB 上有照 MV 版排的歌詞（MV 開頭有 9 秒劇情） |
| A-Lin - 920 | `920 feat` | 9073630 | | |
| A-Lin - 給我一個理由忘記 | `給我一個理由忘記` | 24239455 | | mp3 開頭有 4 秒空白，長度吻合，先不調 |
| BLACKPINK - Pink Venom | `Pink Venom` | 7992688 | | |
| 五月天 - 傷心的人別聽慢歌 | `傷心的人別聽慢歌` | 20257603 | -4300 | MV 開頭 4.65 秒空白；這份歌詞原本是 Live 版的，只是長度剛好吻合 |
| 周杰倫 - 女兒殿下 | `女兒殿下` | 35802548 | | |
| 庾澄慶 - 情非得已 | `情非得已` | 38421611 | | |
| 張震嶽 - 分手吧 | `分手吧` | 16689 | | |
| 張震嶽 - 自由 | `張震嶽 - 自由` | 29760813 | | |
| 桑田佳祐 - 明日晴れるかな | `明日晴れるかな` | 36091080 | | |
| 蔡健雅 - 紅色高跟鞋 | `紅色高跟鞋` | 16205198 | | 快取裡另有一首「蔡健雅 - 紅色高跟鞋」，用的是標題剛好叫「紅色高跟鞋」那首 |
| 鄧紫棋 - 光年之外 | `光年之外` | 11959707 | | |

重抓範例：

```bash
python -X utf8 tools/add_song.py "傷心的人別聽慢歌" --artist "五月天" --track "傷心的人別聽慢歌" --lrclib 20257603 --offset -4300
```

試過但沒收的歌：馬念先 & 9m88〈你朝我的方向走來〉（MV 比歌詞長 23 秒）、動力火車〈跟自己合唱〉（沒有同步歌詞）、李麗芬〈愛江山更愛美人〉（差 4.6 秒）、陶喆〈飛機場的10:30〉（差 6.5 秒）。後兩首補 offset 應該還能救。

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
4. 勾選 **「不明的來源」**（英文介面叫 Unknown sources）
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

Android Auto 設定 → ⋮ → **啟動資訊娛樂主機伺服器**（英文介面叫 Start head unit server）。

確認有開：`adb shell "ss -ltn | grep 5277"` 要看到 `LISTEN`。DHU 顯示「connected」不算數，那只代表連到電腦端的 adb 轉發。

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
| 字幕按鈕（右下角） | 關：標題／副標題換回歌名／歌手，－＋ 鈕隱藏；開：立即回到目前這句。開關狀態會記住 |
| －／＋ 校正鈕 | 副標題顯示「字幕延後／提早 X 秒」約 2 秒後換回下一句；同一首下次播放沿用 |
| 封面 | 每首歌一張色卡，印歌名＋歌手 |

DHU 常用操作：`dayNight` 切換日夜模式、視窗大小可在 DHU 設定檔調整。

## 疑難排解

- **DHU 一直停在 Waiting for phone**：手機上的資訊娛樂主機伺服器沒開，見上面的確認方式
- **DHU 開了馬上自己關掉**：它會讀主控台輸入，從背景執行時讀到檔尾就結束；要在自己的視窗裡開（直接點 exe，或 PowerShell `Start-Process`）
- **按了字幕按鈕畫面沒變**：Auto 的畫面有時會晚一兩秒才更新
- **重裝後按鈕圖示錯亂、App 圖示沒換**：Android Auto 快取了舊 APK 的資源編號。把 DHU 關掉、`adb shell am force-stop com.google.android.projection.gearhead`，再重開資訊娛樂主機伺服器與 DHU
- **某首歌字幕整首早或晚**：先用 －／＋ 鈕調；想寫死在檔案裡就在 LRC 加 `[offset:毫秒]`（正值＝提早）
- **Android Auto 裡看不到 App**：確認「不明的來源」已勾、自訂啟動器有勾選；裝完 App 後把 Android Auto（或 DHU）完全關掉重開
- **看 log**：`adb logcat | grep -iE "karaoke|MediaSession|ExoPlayer"`
- **確認 Session 有在跑**：播放時 `adb shell dumpsys media_session`，應該看到 `com.joechiboo.autokaraoke` 與目前的 metadata
- **歌詞亂碼**：LRC 不是 UTF-8，用編輯器另存成 UTF-8

## 已知限制（POC）

- Android Auto 的佇列（Queue）畫面裡，正在播放那首的標題也會跟著變成目前這句歌詞
- 標題太長時 Android Auto 會截斷；長句建議在 LRC 裡自己斷成兩句
- 歌曲是打包在 APK 裡，換歌要重新編譯
