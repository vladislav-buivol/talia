"""Local OmniVoice service used by the Talia frontend."""

from contextlib import asynccontextmanager
from io import BytesIO
import logging
from os import environ
from pathlib import Path
from threading import Lock
from typing import Literal
from urllib.parse import unquote

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.concurrency import run_in_threadpool
from omnivoice import OmniVoice
from omnivoice.utils.audio import load_audio_bytes
from pydantic import BaseModel, Field, field_validator
import soundfile
import torch
import uvicorn

if __package__:
    from .speech_text import speech_text
    from .voice_context import ContextMessage, InterruptedSpeech, SpeechPlan, language_id, resolve_speech_plan
else:
    from speech_text import speech_text
    from voice_context import ContextMessage, InterruptedSpeech, SpeechPlan, language_id, resolve_speech_plan


VoiceId = Literal["default", "custom"]
MAX_VOICE_BYTES = 20 * 1024 * 1024
logger = logging.getLogger(__name__)


class SpeechSynthesizer:
    def __init__(self, model_path: str, audio: Path, transcript: Path):
        device = "cuda:0" if torch.cuda.is_available() else "cpu"
        dtype = torch.float16 if device == "cuda:0" else torch.float32
        self._model = OmniVoice.from_pretrained(model_path, device_map=device, dtype=dtype)
        self._default_prompt = self._reference_prompt(audio, transcript)
        self._custom = None
        self._lock = Lock()

    def _reference_prompt(self, audio: Path, transcript: Path):
        text = transcript.read_text(encoding="utf-8-sig").strip()
        if not text:
            raise ValueError(f"Voice reference transcript is empty: {transcript}")
        return self._model.create_voice_clone_prompt(ref_audio=str(audio), ref_text=text)

    def load_configured_voice(self, audio: Path, transcript: Path):
        self._custom = (audio.name, self._reference_prompt(audio, transcript))

    def custom_name(self) -> str | None:
        custom = self._custom
        return custom[0] if custom else None

    def set_custom_voice(self, name: str, uploaded: bytes):
        try:
            info = soundfile.info(BytesIO(uploaded))
            if info.format not in {"WAV", "WAVEX", "RF64", "MP3", "MPEG"}:
                raise ValueError("unsupported audio format")
            if not 0 < info.duration <= 60:
                raise HTTPException(422, "Choose a recording of up to 60 seconds.")
            rate = self._model.sampling_rate
            waveform = load_audio_bytes(uploaded, rate)
        except (RuntimeError, ValueError) as error:
            raise HTTPException(422, "Choose a valid WAV or MP3 recording.") from error

        with self._lock:
            try:
                prompt = self._model.create_voice_clone_prompt(ref_audio=(waveform, rate))
                if not prompt.ref_text.strip():
                    raise ValueError("no speech found")
            except ValueError as error:
                raise HTTPException(422, "The recording needs clear speech.") from error
            except (OSError, RuntimeError) as error:
                logger.exception("Could not prepare custom voice")
                raise HTTPException(503, "Could not prepare the recording. Try again.") from error
            self._custom = (name, prompt)

    def to_wav(self, text: str, language: str, voice_id: VoiceId) -> bytes:
        with self._lock:
            if voice_id == "custom" and self._custom is None:
                raise HTTPException(409, "Upload a WAV or MP3 recording first.")
            prompt = self._custom[1] if voice_id == "custom" else self._default_prompt
            waveform = self._model.generate(
                text=text, language=language, voice_clone_prompt=prompt,
            )[0]
        wav = BytesIO()
        soundfile.write(wav, waveform, self._model.sampling_rate, format="WAV", subtype="PCM_16")
        return wav.getvalue()


@asynccontextmanager
async def lifespan(app: FastAPI):
    project_dir = Path(__file__).resolve().parent.parent
    synthesizer = SpeechSynthesizer(
        environ.get("TALIA_TTS_MODEL", "k2-fsa/OmniVoice"),
        project_dir / "voxCPM" / "default_voice.wav",
        project_dir / "tts" / "voice.txt",
    )
    audio = environ.get("TALIA_VOICE_AUDIO")
    transcript = environ.get("TALIA_VOICE_TEXT")
    if audio and transcript:
        synthesizer.load_configured_voice(Path(audio), Path(transcript))
    app.state.synthesizer = synthesizer
    yield


app = FastAPI(lifespan=lifespan)


class PlanRequest(BaseModel):
    text: str
    context: list[ContextMessage] = Field(default_factory=list, max_length=8)
    interrupted: InterruptedSpeech | None = None


class LegacySpeechProfile(BaseModel):
    language: str | None = None


class SpeechRequest(PlanRequest):
    voice: VoiceId = "default"
    language: str | None = None
    profile: LegacySpeechProfile | None = None

    @field_validator("voice", mode="before")
    @classmethod
    def migrate_voice_id(cls, value):
        return "default" if value in ("voxcpm", "designed") else value


@app.get("/api/tts/voice")
def voice_settings():
    return {"customName": app.state.synthesizer.custom_name()}


@app.get("/api/tts/voices", include_in_schema=False)
def legacy_voices():
    voices = [{"id": "default", "label": "Default voice"}]
    custom = app.state.synthesizer.custom_name()
    if custom:
        voices.append({"id": "custom", "label": custom})
    return {"default": "default", "voices": voices}


@app.put("/api/tts/voice")
async def upload_voice(request: Request):
    name = unquote(request.headers.get("X-Voice-File", ""))
    if Path(name).suffix.lower() not in {".wav", ".mp3"}:
        raise HTTPException(422, "Choose a WAV or MP3 recording.")
    if request.headers.get("Content-Type") != "application/octet-stream":
        raise HTTPException(415, "Expected a WAV or MP3 file upload.")
    content = bytearray()
    async for chunk in request.stream():
        if len(content) + len(chunk) > MAX_VOICE_BYTES:
            raise HTTPException(413, "The recording must be 20 MB or smaller.")
        content.extend(chunk)
    await run_in_threadpool(app.state.synthesizer.set_custom_voice, Path(name).name, bytes(content))
    return voice_settings()


@app.post("/api/tts/plan")
def plan_speech(request: PlanRequest) -> SpeechPlan:
    return resolve_speech_plan(request.text, request.context, request.interrupted)


@app.post("/api/tts", response_class=Response)
def synthesize(request: SpeechRequest):
    planned = request.language is not None or request.profile is not None
    legacy_language = request.profile.language if request.profile is not None else None
    language = language_id(request.language or legacy_language)
    text = speech_text(request.text, keep_tags=planned)
    if not text:
        raise HTTPException(status_code=422, detail="Speakable text is required.")
    if not planned:
        plan = plan_speech(request)
        language = language_id(plan.language)
        text = " ".join(segment.text for segment in plan.segments)
        if not text:
            raise HTTPException(status_code=422, detail="Speakable text is required.")
    return Response(
        app.state.synthesizer.to_wav(text, language, request.voice), media_type="audio/wav",
        headers={"Content-Language": language},
    )


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8001)
