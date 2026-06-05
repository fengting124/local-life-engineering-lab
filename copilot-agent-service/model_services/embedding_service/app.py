"""
Embedding Service — intfloat/multilingual-e5-base (default)

设计原则：
- 启动时加载模型，请求时不重复加载
- 自动检测 CUDA，支持 CPU fallback
- e5 系列需要 query:/passage: 前缀，调用方负责添加
- Agent Service 通过 HTTP 调用此服务，不直接 import 模型
"""

import os
import time
import logging
from contextlib import asynccontextmanager
from typing import Optional

import torch
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

# ── 配置（全从环境变量读取）──
MODEL_NAME   = os.getenv("EMBEDDING_MODEL_NAME", "intfloat/multilingual-e5-base")
DIMENSION    = int(os.getenv("EMBEDDING_DIMENSION", "768"))
DEVICE_CFG   = os.getenv("EMBEDDING_DEVICE", "auto")
CACHE_DIR    = os.getenv("MODEL_CACHE_DIR", "/models")
MAX_BATCH    = int(os.getenv("MAX_BATCH_SIZE", "64"))
MAX_TEXT_LEN = int(os.getenv("MAX_TEXT_LENGTH", "2048"))
NORMALIZE    = os.getenv("EMBEDDING_NORMALIZE", "true").lower() == "true"

# ── 全局模型实例（启动时初始化）──
_model: Optional[SentenceTransformer] = None
_device: str = "cpu"


def _resolve_device() -> str:
    if DEVICE_CFG == "auto":
        return "cuda" if torch.cuda.is_available() else "cpu"
    return DEVICE_CFG


def _load_model() -> SentenceTransformer:
    global _model, _device
    _device = _resolve_device()
    logger.info(f"Loading model {MODEL_NAME} on {_device} (cache: {CACHE_DIR})")
    start = time.time()
    model = SentenceTransformer(MODEL_NAME, device=_device, cache_folder=CACHE_DIR)
    elapsed = time.time() - start
    logger.info(f"Model loaded in {elapsed:.1f}s")
    return model


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _model
    _model = _load_model()
    yield
    logger.info("Embedding service shutting down")


app = FastAPI(title="Embedding Service", version="1.0.0", lifespan=lifespan)


# ── 请求 / 响应结构 ──

class EmbedRequest(BaseModel):
    texts: list[str] = Field(..., min_length=1)
    normalize: bool = NORMALIZE


class EmbedResponse(BaseModel):
    model: str
    device: str
    dimension: int
    count: int
    latency_ms: float
    embeddings: list[list[float]]


class HealthResponse(BaseModel):
    status: str
    model: str
    device: str
    dimension: int


# ── 路由 ──

@app.get("/health", response_model=HealthResponse)
def health():
    if _model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    return HealthResponse(status="ok", model=MODEL_NAME, device=_device, dimension=DIMENSION)


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if _model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")

    if len(req.texts) > MAX_BATCH:
        raise HTTPException(
            status_code=400,
            detail=f"Too many texts: {len(req.texts)} > MAX_BATCH_SIZE={MAX_BATCH}"
        )

    for i, t in enumerate(req.texts):
        if len(t) > MAX_TEXT_LEN:
            raise HTTPException(
                status_code=400,
                detail=f"Text[{i}] too long: {len(t)} chars > MAX_TEXT_LENGTH={MAX_TEXT_LEN}"
            )

    start = time.time()
    vecs = _model.encode(
        req.texts,
        normalize_embeddings=req.normalize,
        batch_size=min(len(req.texts), MAX_BATCH),
        show_progress_bar=False,
    )
    latency_ms = (time.time() - start) * 1000

    actual_dim = vecs.shape[1] if len(vecs.shape) == 2 else len(vecs[0])
    if actual_dim != DIMENSION:
        logger.warning(
            f"Actual embedding dimension {actual_dim} != configured EMBEDDING_DIMENSION={DIMENSION}. "
            "Update EMBEDDING_DIMENSION env var."
        )

    return EmbedResponse(
        model=MODEL_NAME,
        device=_device,
        dimension=actual_dim,
        count=len(req.texts),
        latency_ms=round(latency_ms, 2),
        embeddings=vecs.tolist(),
    )
