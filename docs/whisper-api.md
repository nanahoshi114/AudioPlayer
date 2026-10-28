# 听写接口说明

播放器可以按五种接口做听写。本文主要写「本机任务接口」：`POST /jobs`、`GET /jobs/{id}`、`GET /jobs/{id}/cues`。上传后大约每 2 秒查询一次进度。`GET /health` 仍是服务约定，App 当前不请求它。

其余四种是线上模型或兼容 OpenAI 音频转写接口的本机服务，只在下面列流程；字段以各自服务为准。

## 五种接口

在「设置 → 听写和翻译」里选择。每个模型分别保存地址和口令，切换下拉只显示该模型已保存的内容；保存和清除也只作用于当前模型。进行中的任务继续用它自己的模型和口令。

| 界面上的名字 | 地址 | 请求 | 说明 |
| --- | --- | --- | --- |
| `whisper-1` | 固定 `https://api.openai.com`，界面不显示 | `POST /v1/audio/transcriptions` | 只填 API Key。模型名 `whisper-1`，日语，`verbose_json`，分段时间。读超时 10 分钟。超过 25MB 先询问，确认后仍上传，取消记为「已中止」 |
| `whisper（本地部署）` | 用户填写，不能为空 | `POST {地址}/v1/audio/transcriptions` | 请求与 `whisper-1` 相同，模型名仍发送 `whisper-1`。地址没有 `/v1` 时请求前补上。超过 25MB 不询问 |
| `qwen-audio-3.1-asr` | 固定 `https://dashscope.aliyuncs.com`，界面不显示 | 阿里云官方接口 | 只填 API Key。不论时长都先上传到临时存储再轮询，模型名 `qwen-audio-3.1-asr-flash-filetrans`。超过 1GB 不上传。语言提示为日语 |
| `Qwen3-ASR` | 用户填写，不能为空 | `GET {地址}/v1/models`，再 `POST {地址}/v1/audio/transcriptions` | 先读取服务上的模型名，取名字里带 `qwen3-asr` 的第一项；没有就取列表第一项；服务没有列出模型时用 `Qwen/Qwen3-ASR-1.7B`。语言日语，`response_format` 为 `json`。返回分段时间就按段写字幕；只有整段文字时写成覆盖整首时长的一条；没有时长也没有分段时间时记为「没有分段时间」 |
| `本机任务接口` | 用户填写，不能为空 | 本文下面的 `/jobs` 接口 | 应用不发送模型名，用服务自己加载的模型 |

`whisper-1` 和 `qwen-audio-3.1-asr` 是线上模型：界面不显示地址，只保存 API Key，不使用地址栏里留下的内容。`whisper（本地部署）`、`Qwen3-ASR` 和 `本机任务接口` 要填地址和口令；服务不检查口令时，填任意非空内容即可。

没保存过模型时，地址路径去掉末尾斜杠后以 `/v1` 结尾且域名是 `api.openai.com` 的视为 `whisper-1`，其他以 `/v1` 结尾的地址视为 `whisper（本地部署）`，其余视为本机任务接口。保存听写地址时不会自动补 `/v1`；`whisper（本地部署）` 和 `Qwen3-ASR` 只在请求时补。

地址是局域网、本机或 `.local` 时，Android 17 要在请求前申请 `ACCESS_LOCAL_NETWORK`。公网地址不申请。

## 本机任务接口

服务跑在用户自己的电脑上，只听局域网。模型用 faster-whisper 或同级的 Whisper，例如各尺寸的 Whisper 与 Kotoba-Whisper。App 不发送模型名。一次只转写一首，其余排队。解码、切片、把每段时间加回整首，都由服务完成。App 只上传原始音频文件。本文没有取消任务的接口；App 里的「中止」只停止手机上的等待。

### 约定

- 基址由用户填写，例如 `http://192.168.1.20:8765`。不要末尾斜杠。
- 除特别写出的上传外，请求和响应都是 JSON，头为 `Content-Type: application/json`。
- 每个请求带 `Authorization: Bearer <token>`。token 是用户在 App 里填写的口令。缺失或错误返回 `401`，正文 `{"error":"未授权"}`。
- 其余错误也返回 JSON：`{"error":"<给人看的一句说明>"}`。
- 时间单位是毫秒，整数。
- `language` 使用 BCP 47 或 ISO 639-1 短码。日语为 `ja`。缺省 `ja`。

App 会把 `content:` URI 的本地音频先复制进 `filesDir/whisper-src/` 再上传。磁盘上的暂存文件名没有后缀；上传时按文件头补 `mp3`、`flac`、`m4a`、`aac`、`wav` 或 `ogg`。

### GET /health

用来检查服务和模型是否可用。App 当前不请求这一路。

`200`：

```json
{"ok": true, "model": "Kotoba-Whisper"}
```

`model` 是当前加载的模型名，只用于显示。模型未就绪时返回 `503`，`error` 说明原因。

### POST /jobs

上传一首音频并建立任务。不要在这次响应里等待转写结束。

`multipart/form-data` 字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `file` | 是 | 原始音频。接受 m4a、mp3、aac、wav、ogg、flac |
| `fingerprint` | 是 | 文件内容指纹，十六进制字符串。同一文件内容必须稳定 |
| `language` | 否 | 缺省 `ja` |
| `trackId` | 否 | App 里的曲目编号，只作记录，不参与去重 |

去重键是 `fingerprint` + `language`。已有完成或进行中的任务时，返回那次任务，不要重新转写。

`202`：

```json
{"id": "job_1", "status": "queued"}
```

已完成时同样 `202`，`status` 为 `done`。文件缺失或格式不能解码时返回 `400`。

### GET /jobs/{id}

查询进度。未知 `id` 返回 `404`。

`200`：

```json
{"id": "job_1", "status": "running", "progress": 0.4, "error": null}
```

`status` 只允许 `queued`、`running`、`done`、`failed`。`progress` 是 0 到 1 的小数；排队中为 `0`，完成时为 `1`。失败时 `error` 为一句说明，其余状态为 `null`。

### GET /jobs/{id}/cues

取字幕。仅 `done` 返回 `200`。仍在排队或转写返回 `409`。失败返回 `422`，正文沿用该任务的 `error`。未知 `id` 返回 `404`。

`200`：

```json
{
  "language": "ja",
  "cues": [
    {"startMs": 1200, "endMs": 3400, "text": "こんにちは"}
  ]
}
```

`cues` 按 `startMs` 升序。`endMs` 大于 `startMs`。没有人声时 `cues` 为空数组，这是成功，不是失败。`text` 是原文，不要翻译。

这三项对应播放器里的 `SubtitleCue`：`startMs`、`endMs`、`text`。

### 服务必须做到

- 同一进程内同时只跑一首。新任务状态为 `queued`。
- 长音频自行切开再拼接时间，调用方看不到切片。
- 结果按 `fingerprint` + `language` 保留。服务重启后，已完成的任务仍能被 `GET /jobs/{id}/cues` 取到。
- 空转写与解码失败分开：前者 `done` 且 `cues` 为空，后者 `failed`。
