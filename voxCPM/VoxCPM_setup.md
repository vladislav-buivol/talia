# Installing VoxCPM2 on Windows with Conda

This guide sets up the VoxCPM2 web interface in a dedicated Conda environment with NVIDIA CUDA, a local ZipEnhancer model, and a shared FFmpeg build. It uses `C:\tools\VoxCPM` as the installation directory. Run the commands in **Anaconda Prompt**, not PowerShell.

You need Miniconda or Anaconda, Git, 7-Zip, internet access, and an NVIDIA GPU with a compatible driver. Model files require several gigabytes of disk space; the approximate GPU memory requirement is 8 GB. The driver must support CUDA 12.8. CPU inference is also possible, but slower.

## 1. Create the environment and install VoxCPM

```bat
conda create -n voxcpm2 python=3.10 pip -y
conda activate voxcpm2
python -m pip install --upgrade pip
python -m pip install torch==2.10.0 torchaudio==2.10.0 --index-url https://download.pytorch.org/whl/cu128
```

Check GPU detection:

```bat
python -c "import torch; print('PyTorch:', torch.__version__); print('CUDA:', torch.version.cuda); print('GPU:', torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'not found')"
nvidia-smi
```

Create `C:\tools` if it does not exist, then install the project:

```bat
mkdir C:\tools
cd /d C:\tools
git clone https://github.com/OpenBMB/VoxCPM.git
cd VoxCPM
python -m pip install -e .
python -m pip install --force-reinstall --no-deps torchcodec==0.10.0
python -m pip check
```

Skip `mkdir` if the directory already exists. TorchCodec 0.10 is paired here with PyTorch 2.10; check compatibility again if you change the PyTorch version.

## 2. Install shared FFmpeg

Following the SoulX-Singer setup, keep a complete shared FFmpeg build in a separate directory inside the Conda environment:

```bat
if not exist "%CONDA_PREFIX%\ffmpeg-shared" mkdir "%CONDA_PREFIX%\ffmpeg-shared"
curl.exe -fL --retry 2 -o "%TEMP%\ffmpeg-8.1.2-full_build-shared.7z" "https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-8.1.2-full_build-shared.7z"
explorer "%CONDA_PREFIX%\ffmpeg-shared"
```

Open the archive with 7-Zip and copy **all contents of its `bin` folder** into the opened `ffmpeg-shared` folder. The destination should contain `ffmpeg.exe`, `ffprobe.exe`, `avcodec-62.dll`, and the other DLLs directly. Keep all files from the same archive. If the versioned download link no longer works, check the [FFmpeg builds page](https://www.gyan.dev/ffmpeg/builds/).

Test both the executable and audio decoding through TorchCodec:

```bat
set "VOX_FFMPEG_DIR=%CONDA_PREFIX%\ffmpeg-shared"
"%VOX_FFMPEG_DIR%\ffmpeg.exe" -version
"%VOX_FFMPEG_DIR%\ffmpeg.exe" -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=440:duration=1" "%TEMP%\voxcpm-codec-test.wav"
python -c "import os; dlls = os.add_dll_directory(os.environ['VOX_FFMPEG_DIR']); import torchcodec; from torchcodec.decoders import AudioDecoder; a = AudioDecoder(os.path.join(os.environ['TEMP'], 'voxcpm-codec-test.wav')).get_all_samples(); print('TorchCodec:', torchcodec.__version__, '| Sample rate:', a.sample_rate, '| Shape:', tuple(a.data.shape))"
```

## 3. Download and configure ZipEnhancer

With the environment active, download the noise suppression model from ModelScope:

```bat
cd /d C:\tools\VoxCPM
modelscope download --model iic/speech_zipenhancer_ans_multiloss_16k_base pytorch_model.bin configuration.json onnx_model.onnx README.md --local_dir "C:\tools\VoxCPM\models\zipenhancer"
```

Check that the files are directly inside `C:\tools\VoxCPM\models\zipenhancer`. If ModelScope returns HTTP 403 with a too-many-requests message, try again later. In the documented installation, all four files downloaded successfully.

In `C:\tools\VoxCPM\app.py`, find the `VoxCPM.from_pretrained` call in `get_or_load_voxcpm` and specify the local directory:

```python
self.voxcpm_model = voxcpm.VoxCPM.from_pretrained(
    self._model_id,
    load_denoiser=True,
    zipenhancer_model_id=r"C:\tools\VoxCPM\models\zipenhancer",
    optimize=self.optimize,
    device=self.device,
)
```

The version of `app.py` supplied by the user already includes these settings. The code and its location may change when the project is updated.

## 4. Start and test

In Anaconda Prompt:

```bat
conda activate voxcpm2
cd /d C:\tools\VoxCPM
set "VOX_FFMPEG_DIR=%CONDA_PREFIX%\ffmpeg-shared"
set "PATH=%VOX_FFMPEG_DIR%;%PATH%"
python -u -c "import os, runpy; dlls = os.add_dll_directory(os.environ['VOX_FFMPEG_DIR']); runpy.run_path('app.py', run_name='__main__')" --port 8808 --device auto
```

Open [http://127.0.0.1:8808](http://127.0.0.1:8808). On first generation, VoxCPM2 downloads its weights from Hugging Face; automatic reference-audio transcription may require a separate SenseVoice model. Press Ctrl+C in the console to stop the server.

To test noise suppression, upload a reference voice clip, enable **Advanced Settings → Reference audio enhancement**, and generate a short phrase. This option cleans the reference audio before cloning. Downloading ZipEnhancer alone does not prove that audio processing works: check the generated result and the log.

The warning `torch.compile disabled - triton is not installed` means that the acceleration is disabled; it is not a model download error. If the port is busy, stop the previous process or choose another port.

Sources: [VoxCPM installation](https://voxcpm.readthedocs.io/en/latest/installation.html), [quick start](https://voxcpm.readthedocs.io/en/latest/quickstart.html), [TorchCodec compatibility](https://pypi.org/project/torchcodec/0.10.0/).
