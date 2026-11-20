"""Local OmniVoice service used by the Talia frontend."""

from contextlib import asynccontextmanager
from io import BytesIO
from os import environ
from pathlib import Path
from threading import Lock

from fastapi import FastAPI, HTTPException, Response
from omnivoice import OmniVoice
from pydantic import BaseModel, Field
import soundfile
import torch
import uvicorn

if __package__:
    from .voice_context import ContextMessage, DEFAULT_VOICE, VoiceProfile, resolve_voice_profile
else:
    from voice_context import ContextMessage, DEFAULT_VOICE, VoiceProfile, resolve_voice_profile


class SpeechSynthesizer:
    def __init__(self, model_path: str, reference_audio: Path, reference_text: str):
        device = "cuda:0" if torch.cuda.is_available() else "cpu"
        dtype = torch.float16 if device == "cuda:0" else torch.float32
        self._model = OmniVoice.from_pretrained(model_path, device_map=device, dtype=dtype)
        self._voice_prompt = self._model.create_voice_clone_prompt(
            ref_audio=str(reference_audio), ref_text=reference_text
        )
        self._lock = Lock()

    def to_wav(self, text: str, profile: VoiceProfile = DEFAULT_VOICE) -> bytes:
        language = profile.language_id()
        with self._lock:
            waveform = self._model.generate(
                text=text, language=language, instruct=profile.instruction(language),
                voice_clone_prompt=self._voice_prompt,
            )[0]
        wav = BytesIO()
        soundfile.write(wav, waveform, self._model.sampling_rate, format="WAV", subtype="PCM_16")
        return wav.getvalue()


@asynccontextmanager
async def lifespan(app: FastAPI):
    project_dir = Path(__file__).resolve().parent.parent
    reference_audio = Path(environ.get("TALIA_VOICE_AUDIO", str(project_dir / "voice.wav")))
    transcript_file = Path(environ.get("TALIA_VOICE_TEXT", str(project_dir / "tts" / "voice.txt")))
    reference_text = transcript_file.read_text(encoding="utf-8").strip()
    if not reference_text:
        raise ValueError(f"Voice reference transcript is empty: {transcript_file}")
    app.state.synthesizer = SpeechSynthesizer(
        environ.get("TALIA_TTS_MODEL", "k2-fsa/OmniVoice"), reference_audio, reference_text
    )
    yield


app = FastAPI(lifespan=lifespan)


class SpeechRequest(BaseModel):
    text: str
    context: list[ContextMessage] = Field(default_factory=list, max_length=8)


@app.post("/api/tts", response_class=Response)
def synthesize(request: SpeechRequest):
    text = request.text.strip()
    if not text:
        raise HTTPException(status_code=422, detail="Text is required.")

    profile = resolve_voice_profile(text, request.context)
    return Response(
        app.state.synthesizer.to_wav(text, profile), media_type="audio/wav",
        headers={"Content-Language": profile.language_id()},
    )


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8001)
