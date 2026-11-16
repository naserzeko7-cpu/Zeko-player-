# Zeko MADLAD Translation API

This service runs Google's open-weights `google/madlad400-3b-mt` model and exposes:

- `GET /health`
- `POST /translate` with JSON body `{"text":"..." }`

The model is loaded once when the service starts. Translation uses the MADLAD target token `<2ar>`.

## Important hosting and privacy notes

- The model is **not** embedded in the Android APK. The APK sends recognized text (not the video's audio) to the server you configure.
- Model weights are downloaded from Hugging Face on first server startup. The original checkpoint is large; this is not a realistic fit for an ordinary phone/tablet or a small free CPU instance.
- A CUDA GPU with substantial VRAM is recommended. CPU mode is provided for testing but may be extremely slow and memory intensive.
- This repository does not provide a hosted endpoint or promise free hosting. A free notebook/temporary demo may sleep, expire, or impose usage limits.
- Do not expose this unauthenticated API to the public internet. Use it only on a trusted private network or put it behind HTTPS and proper authentication before public deployment.
- Audio never reaches this API. Only the text recognized by Sherpa-ONNX is sent.

## Run

Python 3.10+ is recommended.

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

The first startup downloads the model and may take a while. Check:

```bash
curl http://127.0.0.1:8000/health
curl -X POST http://127.0.0.1:8000/translate \
  -H 'Content-Type: application/json' \
  -d '{"text":"I thought I knew what I wanted, but life surprised me."}'
```

For Android, enter the server base URL (for example, `http://192.168.1.20:8000` when the server and tablet share a trusted Wi-Fi network) when enabling Live AR. Do not use `127.0.0.1` on the tablet; that points to the tablet itself.
