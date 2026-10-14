# 本机听写服务接口

App 已按本文调用本机听写服务。地址的路径不以 `/v1` 结尾时走本文：`POST /jobs`、`GET /jobs/{id}`、`GET /jobs/{id}/cues`。上传后大约每 2 秒查询一次进度。`GET /health` 仍是服务约定，App 当前不请求它。

地址的路径以 `/v1` 结尾时，App 不使用本文，改为一次上传到 `{基址}/audio/transcriptions`。模型名固定 `whisper-1`，`language` 为 `ja`，`response_format` 为 `verbose_json`，并带 `timestamp_granularities[]=segment`。返回里用 `segments` 的 `start`、`end`、`text`，单位是秒。文件大于 25MB 时 App 直接拒绝，不发起请求。单次读取最长等待 10 分钟。

服务跑在用户自己的电脑上，只听局域网。模型用 faster-whisper 或同级的 Whisper，例如各尺寸的 Whisper 与 Kotoba-Whisper。App 不发送模型名。一次只转写一首，其余排队。解码、切片、把每段时间加回整首，都由服务完成。App 只上传原始音频文件。本文没有取消任务的接口；App 里的「中止」只停止手机上的等待。

## 约定

- 基址由用户填写，例如 `http://192.168.1.20:8765`。不要末尾斜杠。
- 除特别写出的上传外，请求和响应都是 JSON，头为 `Content-Type: application/json`。
- 每个请求带 `Authorization: Bearer <token>`。token 是用户在 App 里填写的口令。缺失或错误返回 `401`，正文 `{"error":"未授权"}`。
- 其余错误也返回 JSON：`{"error":"<给人看的一句说明>"}`。
- 时间单位是毫秒，整数。
- `language` 使用 BCP 47 或 ISO 639-1 短码。日语为 `ja`。缺省 `ja`。

## GET /health

用来检查服务和模型是否可用。App 当前不请求这一路。

`200`：

```json
{"ok": true, "model": "Kotoba-Whisper"}
```

`model` 是当前加载的模型名，只用于显示。模型未就绪时返回 `503`，`error` 说明原因。

## POST /jobs

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

## GET /jobs/{id}

查询进度。未知 `id` 返回 `404`。

`200`：

```json
{"id": "job_1", "status": "running", "progress": 0.4, "error": null}
```

`status` 只允许 `queued`、`running`、`done`、`failed`。`progress` 是 0 到 1 的小数；排队中为 `0`，完成时为 `1`。失败时 `error` 为一句说明，其余状态为 `null`。

## GET /jobs/{id}/cues

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

## 服务必须做到

- 同一进程内同时只跑一首。新任务状态为 `queued`。
- 长音频自行切开再拼接时间，调用方看不到切片。
- 结果按 `fingerprint` + `language` 保留。服务重启后，已完成的任务仍能被 `GET /jobs/{id}/cues` 取到。
- 空转写与解码失败分开：前者 `done` 且 `cues` 为空，后者 `failed`。
