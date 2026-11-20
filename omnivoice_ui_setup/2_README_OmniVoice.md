# OmniVoice — Windows 11 / Conda setup

**Two model sources:** the official `k2-fsa/OmniVoice` Hugging Face model repository, or the backup at `https://huggingface.co/buckets/desvlat/OmniVoice-bucket`.

This guide is for **Windows 11**, **NVIDIA RTX 5060 Ti 16 GB**, **Conda**, and **no WSL**. Commands below are intended for **Anaconda Prompt / Miniconda Prompt (`cmd.exe`)**, not PowerShell.

> **Important distinction:** Install the **OmniVoice Python software** only once from PyPI or GitHub. The official repository and the backup bucket are **alternative sources for model weights**; the bucket does *not* replace the Python package. Hugging Face Buckets are not ordinary Hugging Face model repositories, so first download the bucket to disk and then use `--model` with that local path.

## Quick reference

| Task | Command |
|---|---|
| Create environment | `conda create -n omnivoice python=3.11 -y` |
| Activate | `conda activate omnivoice` |
| Install OmniVoice | `python -m pip install omnivoice` |
| Start with official weights | `omnivoice-demo --model k2-fsa/OmniVoice --ip 127.0.0.1 --port 8001` |
| Download backup bucket | `hf buckets sync hf://buckets/desvlat/OmniVoice-bucket C:\AI\Models\OmniVoice` |
| Start with backup weights | `omnivoice-demo --model "C:\AI\Models\OmniVoice" --no-asr --ip 127.0.0.1 --port 8001` |
| Open the UI | <http://127.0.0.1:8001> |

## 1. Prerequisites

- Windows 11, NVIDIA GPU and a working NVIDIA driver (`nvidia-smi` should run).
- Conda or Miniconda installed. Open an **Anaconda Prompt**.
- Several gigabytes of free disk space for model weights, environment, and optional ASR model.
- Python **3.11** is used here (the project declares Python `>=3.10`).

Check prerequisites:

```bat
conda --version
nvidia-smi
```

## 2. Create an isolated Conda environment

```bat
conda create -n omnivoice python=3.11 -y
conda activate omnivoice
python --version
python -m pip install --upgrade pip
```

If you have already created `omnivoice`, skip `conda create` and just activate it.

## 3. Install CUDA-enabled PyTorch

The official project gives PyTorch 2.8.0 / CUDA 12.8 as an NVIDIA example:

```bat
python -m pip install torch==2.8.0+cu128 torchaudio==2.8.0+cu128 --extra-index-url https://download.pytorch.org/whl/cu128
```

Verify CUDA:

```bat
python -c "import torch; print('Torch:', torch.__version__); print('CUDA:', torch.cuda.is_available()); print('GPU:', torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'unavailable')"
```

You should see `CUDA: True` and your NVIDIA GPU. This PyTorch wheel includes the necessary CUDA runtime components; a separate CUDA Toolkit is generally not required for ordinary inference.

## 4. Install the OmniVoice Python package (same for both sources)

Use the stable PyPI version:

```bat
python -m pip install omnivoice
```

**OR** use the newest code from GitHub (requires Git):

```bat
python -m pip install git+https://github.com/k2-fsa/OmniVoice.git
```

Choose **one** method, not both. Install the Hugging Face CLI for downloading the backup:

```bat
python -m pip install --upgrade huggingface_hub
python -m pip check
omnivoice-demo --help
```

> If installing or upgrading dependencies replaces your GPU-enabled PyTorch build, rerun the CUDA verification command. Keep this isolated from your other Python environments.

---

# Source A — Official model (recommended for ordinary use)

**Weights:** <https://huggingface.co/k2-fsa/OmniVoice>  
**Code:** <https://github.com/k2-fsa/OmniVoice>

### Start the Gradio web UI

```bat
conda activate omnivoice
omnivoice-demo --model k2-fsa/OmniVoice --ip 127.0.0.1 --port 8001
```

Open <http://127.0.0.1:8001>. The model can be downloaded into the Hugging Face cache on the first run. By default, the demo can also load **Whisper ASR** so that reference audio may be transcribed automatically; this may download **additional** weights.

To run without the optional Whisper model, use:

```bat
omnivoice-demo --model k2-fsa/OmniVoice --no-asr --ip 127.0.0.1 --port 8001
```

With `--no-asr`, provide the reference transcription (`ref_text`) yourself when cloning a voice.

### Optional: store official weights in a known local directory

```bat
mkdir C:\AI\Models
hf download k2-fsa/OmniVoice --local-dir C:\AI\Models\OmniVoice-Official
omnivoice-demo --model "C:\AI\Models\OmniVoice-Official" --no-asr --ip 127.0.0.1 --port 8001
```

The local directory is useful even if the official repository later becomes unavailable.

---

# Source B — Your Hugging Face bucket backup

**Backup URL:** <https://huggingface.co/buckets/desvlat/OmniVoice-bucket>

The bucket presently lists the main `model.safetensors` plus `config.json`, tokenizer files, and an `audio_tokenizer/` subtree. Download **all** files, retaining the original directory structure. This is a *copy of model data*, not an alternative installation of the OmniVoice Python package.

### Step B1: Download the complete bucket to disk

```bat
conda activate omnivoice
python -m pip install --upgrade huggingface_hub
mkdir C:\AI\Models
hf buckets sync hf://buckets/desvlat/OmniVoice-bucket C:\AI\Models\OmniVoice
```

This is the documented Hugging Face CLI syntax for **bucket → local folder**. The command synchronizes the directory; it is not the same as `hf download` for model repositories.

If you get a permission/authentication error for a private bucket:

```bat
hf auth login
```

Sign in using an account that can read the bucket, then retry the sync command. A public bucket may not require login.

### Step B2: Check downloaded files

```bat
dir C:\AI\Models\OmniVoice

dir C:\AI\Models\OmniVoice\audio_tokenizer
```

At a minimum, look for:

```text
C:\AI\Models\OmniVoice\
  config.json
  model.safetensors
  tokenizer.json
  tokenizer_config.json
  chat_template.jinja
  audio_tokenizer\
    config.json
    model.safetensors
    preprocessor_config.json
```

Other files may be present and should be retained. The bucket's present contents resemble the official model layout, but complete inference compatibility still must be confirmed by running a test.

### Step B3: Start OmniVoice from the local backup

```bat
conda activate omnivoice
omnivoice-demo --model "C:\AI\Models\OmniVoice" --no-asr --ip 127.0.0.1 --port 8001
```

Open <http://127.0.0.1:8001>.

**Why `--no-asr`?** It skips downloading/loading the separate Whisper automatic transcription model. You can still use voice cloning, but must supply reference text yourself.

### Step B4: Optional: explicitly prohibit Hugging Face network downloads

In the same Anaconda Prompt:

```bat
set HF_HUB_OFFLINE=1
omnivoice-demo --model "C:\AI\Models\OmniVoice" --no-asr --ip 127.0.0.1 --port 8001
```

This enforces Hugging Face Hub offline mode for that terminal. It will expose any missing local resources instead of silently fetching them. **It does not guarantee that every possible Python dependency or feature is fully offline.** To undo it in the same terminal: `set HF_HUB_OFFLINE=`.

### Step B5: Update the local backup later

```bat
conda activate omnivoice
hf buckets sync hf://buckets/desvlat/OmniVoice-bucket C:\AI\Models\OmniVoice
```

By default this adds/updates files and does not delete extra local files. Avoid `--delete` unless you explicitly want the local directory to mirror the bucket exactly.

---

## 5. Launch with a double-click (`.bat` files)

You can save the following as `start_omnivoice_official.bat` and double-click it from Windows. It assumes `conda.bat` is discoverable on `PATH`; if not, replace `CONDA_BAT` with your full path to Conda's `condabin\conda.bat`.

```bat
@echo off
setlocal
set "CONDA_BAT=conda.bat"
call "%CONDA_BAT%" activate omnivoice
if errorlevel 1 goto :error
omnivoice-demo --model k2-fsa/OmniVoice --ip 127.0.0.1 --port 8001
pause
exit /b 0
:error
echo Failed to activate Conda environment or find Conda.
pause
exit /b 1
```

For the backup, save the following as `start_omnivoice_bucket.bat`:

```bat
@echo off
setlocal
set "CONDA_BAT=conda.bat"
set "MODEL_DIR=C:\AI\Models\OmniVoice"
if not exist "%MODEL_DIR%\model.safetensors" goto :missing
if not exist "%MODEL_DIR%\audio_tokenizer\model.safetensors" goto :missing
call "%CONDA_BAT%" activate omnivoice
if errorlevel 1 goto :error
set HF_HUB_OFFLINE=1
omnivoice-demo --model "%MODEL_DIR%" --no-asr --ip 127.0.0.1 --port 8001
pause
exit /b 0
:missing
echo Model files missing. Download the bucket first.
goto :error
:error
echo Could not launch OmniVoice.
pause
exit /b 1
```

If running from a normal double-click fails because `conda.bat` is not on `PATH`, use the launcher in **Anaconda Prompt** or set, for example:

```bat
set "CONDA_BAT=C:\Users\YOUR_USERNAME\miniconda3\condabin\conda.bat"
```

## 6. Quick Russian synthesis test (without Gradio)

Create `test_ru.py`:

```python
from omnivoice import OmniVoice
import soundfile as sf
import torch

# Choose ONE:
SOURCE = "k2-fsa/OmniVoice"          # official Hugging Face model
# SOURCE = r"C:\AI\Models\OmniVoice"  # local bucket backup

model = OmniVoice.from_pretrained(
    SOURCE,
    device_map="cuda:0",
    dtype=torch.float16,
    load_asr=False,
)

waveforms = model.generate(
    text="Привет! Это проверка синтеза русской речи.",
    num_step=32,
)
sf.write("russian_test.wav", waveforms[0], 24000)
print("Saved russian_test.wav")
```

Run:

```bat
conda activate omnivoice
python test_ru.py
```

For voice cloning, supply a short authorized `ref_audio` and its exact `ref_text`. A reference in the same language is recommended; cross-language cloning may preserve an accent from the reference audio.

## 7. Troubleshooting

| Symptom | Check / fix |
|---|---|
| `conda` not found | Use Anaconda Prompt or locate `condabin\conda.bat`. |
| `omnivoice-demo` not found | `conda activate omnivoice`, then `python -m pip show omnivoice` and `where omnivoice-demo`. |
| CUDA unavailable | Run `nvidia-smi`, verify the PyTorch CUDA wheel, inspect `torch.cuda.is_available()`. |
| Bucket access denied | Check bucket permissions; run `hf auth login` if appropriate. |
| `model.safetensors` missing | Run bucket sync and check destination; it must be the directory supplied to `--model`. |
| Whisper downloads unexpectedly | Add `--no-asr` and provide your reference text manually. |
| Local backup still attempts downloads | Confirm you pass a **local path**, use `--no-asr`, and optionally set `HF_HUB_OFFLINE=1`. |
| CUDA out of memory | Close competing GPU applications; try smaller workloads or fewer diffusion steps. |
| Low-quality voice cloning | Use a clean ~3–10 s reference and its accurate transcript, preferably in the target language. |

## 8. Backup limitations, licensing and safety

- The bucket is hosted by **Hugging Face**, just like the official repository. If the official **model repository** disappears, the bucket may remain accessible; if **Hugging Face itself** is unavailable, only a previously downloaded local copy will help.
- Model weights in the bucket must be complete and compatible with your installed OmniVoice code. Consider saving a tested environment package list with `python -m pip freeze > omnivoice-requirements.txt`.
- The OmniVoice **code** is Apache-2.0. The **pretrained model weights** are **CC-BY-NC** (non-commercial). Copying weights into a personal bucket does not change their license.
- Use voice cloning only with appropriate consent and rights.

## References (verified 2026-11-04)

1. Official GitHub README and CLI docs: <https://github.com/k2-fsa/OmniVoice>
2. Official model: <https://huggingface.co/k2-fsa/OmniVoice>
3. Demo CLI implementation (`--model`, `--no-asr`): <https://github.com/k2-fsa/OmniVoice/blob/master/omnivoice/cli/demo.py>
4. Your backup: <https://huggingface.co/buckets/desvlat/OmniVoice-bucket>
5. HF bucket syncing: <https://huggingface.co/docs/huggingface_hub/en/guides/buckets>

