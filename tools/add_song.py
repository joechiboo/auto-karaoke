"""加一首歌：從 Argus 點唱機快取下載 mp3，從 LRCLIB 抓同步歌詞，寫進 assets/songs/。

用法：
    set ARGUS_URL=http://<argus 主機>:<埠>      （或寫在 tools/local.env：ARGUS_URL=...）
    python -X utf8 tools/add_song.py "Queencard" --artist "(G)I-DLE" --track "Queencard"

    --keyword（第一個參數）：在 Argus 快取標題裡找的關鍵字
    --artist / --track：查 LRCLIB 用的歌手與歌名，也是檔名「歌手 - 歌名」
    --lrclib <id>：指定用哪一份歌詞（看完候選清單後再跑一次）
    --offset <毫秒>：寫進 LRC 的 [offset:]，正值＝歌詞提早
    --force：長度差超過門檻也照寫

判斷規則：
- LRCLIB 每份歌詞附歌曲長度。跟 mp3 長度差 <= 2 秒才自動採用，否則只列候選。
  MV 版常比錄音室版多前奏或劇情，歌詞會整首偏掉；有些人會上傳照 MV 版排的歌詞，
  長度會剛好對上，那份就能直接用。
- 長度吻合不保證每句都準：唱的時候再用播放畫面的 －／＋ 鈕微調。

注意：LRCLIB 是外部網站，請求之間固定隔 2 秒，不要拿來大量爬。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SONGS = ROOT / "app" / "src" / "main" / "assets" / "songs"
UA = {"User-Agent": "auto-karaoke/0.1 (personal POC)"}
MAX_DIFF = 2.0


def argus_url() -> str:
    url = os.environ.get("ARGUS_URL")
    env = Path(__file__).with_name("local.env")
    if not url and env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith("ARGUS_URL="):
                url = line.split("=", 1)[1].strip()
    if not url:
        sys.exit("請設定 ARGUS_URL（環境變數或 tools/local.env）")
    return url.rstrip("/")


def get_json(url: str, headers=None):
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=30) as r:
        return json.load(r)


def probe(url: str) -> tuple[float, float]:
    """回傳 (長度秒, 開頭空白秒)。"""
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                          "-of", "csv=p=0", url], capture_output=True, text=True)
    dur = float(out.stdout.strip())
    err = subprocess.run(["ffmpeg", "-hide_banner", "-t", "20", "-i", url, "-af",
                          "silencedetect=noise=-40dB:d=0.3", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    m = re.search(r"silence_start: 0(?:\.0+)?\s.*?silence_end: ([\d.]+)", err, re.S)
    return dur, float(m.group(1)) if m else 0.0


def search_lrclib(artist: str, track: str) -> list[dict]:
    found: dict[int, dict] = {}
    for q in ({"track_name": track, "artist_name": artist}, {"track_name": track}):
        res = get_json("https://lrclib.net/api/search?" + urllib.parse.urlencode(q), UA)
        time.sleep(2)
        for e in res:
            if e.get("syncedLyrics"):
                found[e["id"]] = e
        if found:
            break
    return list(found.values())


def first_line_sec(lrc: str) -> float:
    m = re.search(r"^\[(\d+):(\d+(?:\.\d+)?)\]", lrc, re.M)
    return int(m.group(1)) * 60 + float(m.group(2)) if m else 0.0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("keyword")
    ap.add_argument("--artist", required=True)
    ap.add_argument("--track", required=True)
    ap.add_argument("--lrclib", type=int)
    ap.add_argument("--offset", type=int, default=0)
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()

    base_url = argus_url()
    cache = get_json(f"{base_url}/api/music/cache")["tracks"]
    hits = [c for c in cache if a.keyword.lower() in (c.get("title") or "").lower()]
    exact = [c for c in hits if (c.get("title") or "").strip().lower() == a.keyword.strip().lower()]
    if len(exact) == 1:  # 標題跟關鍵字一字不差的優先（例如快取裡同時有「紅色高跟鞋」和「蔡健雅 - 紅色高跟鞋」）
        hits = exact
    if not hits:
        sys.exit(f"Argus 快取裡找不到「{a.keyword}」")
    if len(hits) > 1:
        print("找到多首，換更精確的關鍵字：")
        for c in hits:
            print("  ", c["title"])
        sys.exit(1)
    hit = hits[0]
    audio_url = f'{base_url}/api/music/cache/{hit["videoId"]}'
    dur, lead = probe(audio_url)
    print(f'Argus：{hit["title"]}\n  長度 {dur:.1f}s，開頭空白 {lead:.2f}s')

    candidates = search_lrclib(a.artist, a.track)
    if not candidates:
        sys.exit("LRCLIB 沒有同步歌詞")
    candidates.sort(key=lambda e: abs((e.get("duration") or 0) - dur))

    if a.lrclib:
        chosen = next((e for e in candidates if e["id"] == a.lrclib), None)
        if not chosen:
            chosen = get_json(f"https://lrclib.net/api/get/{a.lrclib}", UA)
    else:
        chosen = candidates[0]

    diff = dur - (chosen.get("duration") or 0)
    print("LRCLIB 候選（長度最接近的前 5 份）：")
    for e in candidates[:5]:
        d = dur - (e.get("duration") or 0)
        mark = "←" if e["id"] == chosen["id"] else " "
        print(f'  {mark} #{e["id"]:<10} 長度差 {d:+6.1f}s  第一句 {first_line_sec(e["syncedLyrics"]):6.2f}s  '
              f'{e["artistName"]} - {e["trackName"]} | {e.get("albumName")}')

    if abs(diff) > MAX_DIFF and not a.force:
        sys.exit(f"\n長度差 {diff:+.1f}s 超過 {MAX_DIFF}s，沒有寫檔。"
                 f"\n可能是 MV 版多了前奏；用 --lrclib 指定一份、--offset 補差距，或 --force 照寫。")

    name = re.sub(r'[\\/:*?"<>|]', "_", f"{a.artist} - {a.track}")
    SONGS.mkdir(parents=True, exist_ok=True)
    mp3 = SONGS / f"{name}.mp3"
    if not mp3.exists():
        urllib.request.urlretrieve(audio_url, mp3)
    body = "\n".join(l for l in chosen["syncedLyrics"].splitlines() if not re.match(r"^\[(ti|ar|offset):", l, re.I))
    header = f'[ti:{a.track}]\n[ar:{a.artist}]\n[by:lrclib #{chosen["id"]}]\n'
    if a.offset:
        header += f"[offset:{a.offset}]\n"
    (SONGS / f"{name}.lrc").write_text(header + body + "\n", encoding="utf-8")
    print(f"\n已寫入 {name}.mp3 / .lrc（長度差 {diff:+.1f}s）。重新編譯安裝：./gradlew installDebug")


if __name__ == "__main__":
    main()
