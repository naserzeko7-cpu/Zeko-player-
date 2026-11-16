import os
from contextlib import asynccontextmanager

import torch
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

MODEL_ID = os.getenv("MADLAD_MODEL_ID", "google/madlad400-3b-mt")
tokenizer = None
model = None
device = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global tokenizer, model, device
    device = "cuda" if torch.cuda.is_available() else "cpu"
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
    kwargs = {"low_cpu_mem_usage": True}
    if device == "cuda":
        kwargs["torch_dtype"] = torch.float16
        kwargs["device_map"] = "auto"
    model = AutoModelForSeq2SeqLM.from_pretrained(MODEL_ID, **kwargs)
    if device == "cpu":
        model.to(device)
    yield
    model = None
    tokenizer = None

app = FastAPI(title="Zeko MADLAD Translation API", version="0.1.0", lifespan=lifespan)

class TranslationRequest(BaseModel):
    text: str = Field(min_length=1, max_length=5000)

@app.get("/health")
def health():
    return {"ok": True, "model_loaded": model is not None, "model": MODEL_ID}

@app.post("/translate")
def translate(request: TranslationRequest):
    if model is None or tokenizer is None:
        raise HTTPException(status_code=503, detail="MADLAD model is still loading")
    text = request.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Text is empty")
    try:
        encoded = tokenizer("<2ar> " + text, return_tensors="pt", truncation=True, max_length=512)
        encoded = {key: value.to(model.device) for key, value in encoded.items()}
        with torch.inference_mode():
            generated = model.generate(
                **encoded,
                max_new_tokens=220,
                num_beams=4,
                do_sample=False,
                early_stopping=True,
            )
        result = tokenizer.decode(generated[0], skip_special_tokens=True).strip()
        if not result:
            raise HTTPException(status_code=502, detail="Model returned an empty translation")
        return {"translation": result}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Translation failed: {type(exc).__name__}") from exc
