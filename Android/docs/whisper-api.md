# 听写接口说明

播放器可以按三种接口做听写。两种是线上模型或兼容 OpenAI 音频转写接口的自部署服务，只在下面列流程；SenseVoiceSmall 完全在手机本机运行。

## 三种接口

在「设置 → 听写和翻译」里选择。每个模型分别保存地址和口令（SenseVoiceSmall 没有地址和口令），切换下拉只显示该模型已保存的内容；保存和清除也只作用于当前模型。进行中的任务继续用它自己的模型和口令。

| 界面上的名字 | 地址 | 请求 | 说明 |
| --- | --- | --- | --- |
| `qwen-audio-3.1-asr` | 固定 `https://dashscope.aliyuncs.com`，界面不显示 | 阿里云官方接口 | 只填 API Key。不论时长都先上传到临时存储再轮询，模型名 `qwen-audio-3.1-asr-flash-filetrans`。超过 1GB 不上传。语言提示为日语 |
| `Qwen3-ASR` | 用户填写，不能为空 | `GET {地址}/v1/models`，再 `POST {地址}/v1/audio/transcriptions` | 先读取服务上的模型名，取名字里带 `qwen3-asr` 的第一项；没有就取列表第一项；服务没有列出模型时用 `Qwen/Qwen3-ASR-1.7B`。语言日语，`response_format` 为 `json`。返回分段时间就按段写字幕；只有整段文字时写成覆盖整首时长的一条；没有时长也没有分段时间时记为「没有分段时间」 |
| `SenseVoiceSmall` | 无，本机运行 | 无网络请求 | 先在设置里下载模型，之后完全离线。见下节 |

`qwen-audio-3.1-asr` 是线上模型：界面不显示地址，只保存 API Key，不使用地址栏里留下的内容。`Qwen3-ASR` 要填地址和口令；服务不检查口令时，填任意非空内容即可。保存听写地址时不会自动补 `/v1`；`Qwen3-ASR` 只在请求时补。

地址是局域网、本机或 `.local` 时（只可能是 `Qwen3-ASR`），Android 17 要在请求前申请 `ACCESS_LOCAL_NETWORK`。公网地址不申请。

## SenseVoiceSmall（本机模型）

完全在手机上运行的离线听写，不需要地址和口令，也不会把音频发到任何服务器。

- **模型下载**：第一次使用前，在「设置 → 听写和翻译」选 `SenseVoiceSmall` 后点「下载模型」。模型从 GitHub Releases（`k2-fsa/sherpa-onnx`）直接下载到手机，压缩包约 160MB，解压后占用约 230MB，保存在 `filesDir/sense-voice/`。下载时显示进度，可以取消；失败可以重试。设置里也可以删除模型释放空间。
- **运行方式**：用 sherpa-onnx 在本机 CPU 上推理（vendored 的 `com.k2fsa.sherpa.onnx` Kotlin API 和 `arm64-v8a` 动态库，只带 arm64）。
- **长音频分段**：SenseVoice 单段上限约 30 秒，模型不会自己切开。App 用 Silero VAD（`silero_vad.onnx`，随 APK 打包在 assets 里）按静音把音频切成不超过 28 秒的语音段，逐段识别，再按每段 token 时间戳加回整首音轨的时间轴。
- **识别语言**：日语。
- **进度与中止**：按已识别的音频占比显示进度。「中止」在当前片段识别结束后停止，通常一两秒内生效。
- **解码**：用 MediaExtractor + MediaCodec 把音频流式解码成 16kHz 单声道 PCM，一次只占一块内存，不把整首音频读进内存。个别设备对 FLAC/OGG 没有解码器时，记为「音频解码失败」，不会崩溃。
