# Weather Model Training & Conversion Guide

This guide explains how to fine-tune a MobileNetV3 model for weather classification
and convert it to TensorFlow Lite for use in the YachtApp Android app.

---

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Dataset Preparation](#dataset-preparation)
4. [Training](#training)
5. [Conversion to TFLite](#conversion-to-tflite)
6. [Android Integration](#android-integration)
7. [Troubleshooting](#troubleshooting)

---

## Overview

| Step | Script | Input | Output |
|------|--------|-------|--------|
| 1 | `train_weather_model.py` | HuggingFace dataset | `weather_model.pt`, `weather_model.onnx` |
| 2 | `convert_to_tflite.py` | `weather_model.onnx` | `weather_model.tflite` |

### Target Weather Classes
- `sunny` - Clear skies, good visibility
- `cloudy` - Overcast, partial cloud cover
- `rainy` - Precipitation visible
- `foggy` - Reduced visibility, mist
- `storm` - Thunderstorms, heavy rain
- `clear` - Unobstructed sky
- `snowy` - Snow visible (if applicable)
- `overcast` - Full cloud cover
- `hazy` - Atmospheric haze
- `night` - Low light conditions

---

## Prerequisites

### Python Environment
```bash
# Create virtual environment (recommended)
python -m venv .venv
source .venv/bin/activate   # Linux/Mac
# .venv\Scripts\activate    # Windows

# Install dependencies
pip install -r requirements.txt
```

### requirements.txt
```
torch>=2.0.0
torchvision>=0.15.0
transformers>=4.30.0
datasets>=2.14.0
accelerate>=0.21.0
onnx>=1.14.0
onnx2tf>=0.3.0
tensorflow>=2.13.0
Pillow
matplotlib
```

### Hardware Requirements
- **CPU**: Minimum 8 GB RAM, ~2-4 hours training time
- **GPU (Recommended)**: NVIDIA GPU with 8+ GB VRAM, ~30-60 minutes
- **Storage**: ~5 GB for dataset + models

---

## Dataset Preparation

### Option A: Use `weather-cam-dataset` (Recommended)
This dataset is pre-labeled and ready to use:

```python
from datasets import load_dataset

# Loads directly from HuggingFace
dataset = load_dataset("weather-cam-dataset")
```

**Dataset structure:**
```
weather-cam-dataset/
├── train/
│   ├── sunny/     (images)
│   ├── cloudy/
│   ├── rainy/
│   └── ...
├── validation/
│   └── ...
└── test/
    └── ...
```

### Option B: Use `weather-14` Dataset
Another option from HuggingFace with 14 weather classes:

```python
dataset = load_dataset("weather-14")
```

### Option C: Custom Dataset
If using your own dataset, structure it as:

```
custom_dataset/
├── train/
│   ├── sunny/
│   │   ├── img1.jpg
│   │   └── img2.jpg
│   ├── cloudy/
│   │   ├── img3.jpg
│   │   └── img4.jpg
│   └── ...
└── validation/
    └── ...
```

Then load with:
```python
dataset = load_dataset("imagefolder", data_dir="custom_dataset")
```

---

## Training

### Quick Start
```bash
python scripts/train_weather_model.py
```

### With Custom Parameters
```bash
python scripts/train_weather_model.py \
    --model_name google/mobilenet_v3_large \
    --dataset_name weather-cam-dataset \
    --num_epochs 15 \
    --batch_size 32 \
    --learning_rate 1e-5
```

### Monitoring Training
Training metrics are saved to:
- `models/training_metrics.json` - Loss, accuracy per epoch
- `models/trainer_output/` - Checkpoints (best model auto-saved)

### Expected Output
```
=== Starting training ===
Epoch 1/10 - train_loss: 1.234 - eval_accuracy: 0.456
Epoch 2/10 - train_loss: 0.891 - eval_accuracy: 0.678
...
Epoch 10/10 - train_loss: 0.123 - eval_accuracy: 0.945
=== Training complete ===
```

### Output Files
| File | Description |
|------|-------------|
| `models/weather_model.pt` | PyTorch state dict |
| `models/weather_model.onnx` | ONNX model (for TFLite) |
| `models/training_metrics.json` | Training history |

---

## Conversion to TFLite

### FP16 Quantization (Recommended)
Best balance of accuracy and performance for most Android devices:

```bash
python scripts/convert_to_tflite.py --quantize fp16
```

### INT8 Quantization (Maximum Compression)
Smallest model size, may have slight accuracy drop:

```bash
python scripts/convert_to_tflite.py --quantize int8
```

### Full Precision (No Quantization)
Largest model, maximum accuracy:

```bash
python scripts/convert_to_tflite.py --quantize none
```

### With Calibration Data (for INT8)
```bash
python scripts/convert_to_tflite.py \
    --quantize int8 \
    --calibration_dir path/to/calibration/images
```

### Output Files
| File | Size (approx) | Use Case |
|------|---------------|----------|
| `weather_model.tflite` | ~4-6 MB | Default (FP16) |
| `weather_model_int8.tflite` | ~1-2 MB | Maximum compression |
| `weather_model_fp16.tflite` | ~4-6 MB | Balanced |

---

## Android Integration

### 1. Copy Model to Assets
```
app/src/main/assets/weather_model.tflite
```

### 2. Add Dependencies
In `app/build.gradle.kts`:
```kotlin
dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:2.14.0")
}
```

### 3. Load & Run Model
```kotlin
class WeatherClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val labelList = listOf(
        "sunny", "cloudy", "rainy", "foggy",
        "storm", "clear", "snowy", "overcast",
        "hazy", "night"
    )

    init {
        loadModel()
    }

    private fun loadModel() {
        val modelFile = loadModelFile("weather_model.tflite")
        interpreter = Interpreter(modelFile)
    }

    fun classify(image: Bitmap): String {
        val input = preprocess(image)
        val output = Array(1)(floatArrayOf(0f))
        interpreter?.run(input, output)
        return labelList[output[0].argmax()]
    }

    private fun preprocess(image: Bitmap): ByteBuffer {
        // Resize to 224x224, normalize to [0, 1]
        // Convert to ByteBuffer with shape [1, 224, 224, 3]
        // ...
    }

    fun close() {
        interpreter?.close()
    }
}
```

---

## Troubleshooting

### Dataset Loading Errors
```
ValueError: Dataset 'weather-cam-dataset' not found
```
**Solution:** Ensure internet connection, or download dataset manually:
```python
from huggingface_hub import snapshot_download
snapshot_download("weather-cam-dataset", local_dir="./datasets/weather-cam-dataset")
```

### CUDA / GPU Errors
```
RuntimeError: CUDA out of memory
```
**Solution:** Reduce batch size in `train_weather_model.py`:
```python
BATCH_SIZE = 8  # Instead of 16
```

### ONNX Export Fails
```
Exporting model to ONNX format...
Error: Unsupported operator
```
**Solution:** Update onnx and onnxruntime:
```bash
pip install --upgrade onnx onnxruntime
```

### TFLite Conversion Fails
```
ConverterError: Failed to convert model
```
**Solution:** 
1. Check ONNX model validity: `python -c "import onnx; onnx.checker.check_model('weather_model.onnx')"`
2. Use `--quantize none` first to verify base conversion works
3. Try `onnx2tf` with `--non_verbose False` for detailed logs

### Low Accuracy
- Increase `NUM_EPOCHS` to 15-20
- Use `google/mobilenet_v3_large` instead of `small`
- Increase dataset size
- Try data augmentation in training script
- Use `--quantize fp16` instead of `int8`

---

## Model Performance Expectations

| Metric | Expected Value |
|--------|----------------|
| Training Accuracy | 85-95% |
| Validation Accuracy | 80-90% |
| Model Size (FP16) | 4-6 MB |
| Model Size (INT8) | 1-2 MB |
| Inference Time (GPU) | 10-30 ms |
| Inference Time (CPU) | 50-150 ms |

---

## License Notes
- MobileNetV3: BSD-3-Clause (Google)
- MobileViT: Apache 2.0 (Apple)
- Check dataset licenses before commercial use
