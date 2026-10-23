"""
train_weather_model.py
======================
Fine-tunes a MobileNetV3 model for weather classification
using the `prithivMLmods/WeatherNet-05-18039` dataset from Hugging Face.

Usage:
    python scripts/train_weather_model.py

Output:
    weather_model.pt          - PyTorch checkpoint (state dict)
    weather_model.onnx        - ONNX export for TFLite conversion
    training_metrics.json     - Training history (loss, accuracy per epoch)
"""

import json
import logging
from pathlib import Path

import torch
from datasets import ClassLabel, Image, load_dataset
from transformers import (
    AutoImageProcessor,
    AutoModelForImageClassification,
    Trainer,
    TrainingArguments,
)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
MODEL_NAME = "timm/mobilenetv3_small_100.lamb_in1k"
DATASET_NAME = "prithivMLmods/WeatherNet-05-18039"

OUTPUT_DIR = Path(__file__).resolve().parent.parent / "models"
CHECKPOINT_PATH = OUTPUT_DIR / "weather_model.pt"
ONNX_PATH = OUTPUT_DIR / "weather_model.onnx"
METRICS_PATH = OUTPUT_DIR / "training_metrics.json"
MAX_SAMPLES = None  # Задайте int (например, 1000) для быстрого тестирования пайплайна

# Training hyper-parameters
NUM_EPOCHS = 10
BATCH_SIZE = 16
LEARNING_RATE = 2e-5
WEIGHT_DECAY = 0.01
FP16 = torch.cuda.is_available()

WEATHER_LABELS = [
    "cloudy_overcast",
    "foggy_hazy",
    "rain_storm",
    "snow_frosty",
    "sun_clear",
]

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def ensure_output_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)


def load_and_prepare_dataset() -> tuple[dict, str, str]:
    logger.info(f"Loading dataset: {DATASET_NAME}")
    try:
        raw_dataset = load_dataset(DATASET_NAME)
    except Exception as exc:
        logger.error(f"Failed to load dataset: {exc}")
        raise

    # 1. Формирование сплитов
    if "validation" not in raw_dataset:
        if "test" in raw_dataset:
            split_dataset = raw_dataset
            split_dataset["validation"] = split_dataset.pop("test")
        else:
            logger.info("Split 'validation' not found. Splitting 'train' (90/10)...")
            split = raw_dataset["train"].train_test_split(test_size=0.1, seed=42)
            split_dataset = {
                "train": split["train"],
                "validation": split["test"],
            }
    else:
        split_dataset = raw_dataset

    # 2. Определение реальных названий колонок
    first_sample = split_dataset["train"][0]
    sample_keys = list(first_sample.keys())
    logger.info(f"Dataset sample keys: {sample_keys}")

    image_col = None
    for k, v in first_sample.items():
        if hasattr(v, "convert") or "image" in k.lower() or "img" in k.lower():
            image_col = k
            break
    if not image_col:
        image_col = sample_keys[0]

    label_col = None
    for k in sample_keys:
        if k != image_col and ("label" in k.lower() or "target" in k.lower()):
            label_col = k
            break
    if not label_col:
        label_col = [k for k in sample_keys if k != image_col][0]

    logger.info(f"Using image column: '{image_col}', label column: '{label_col}'")

    if MAX_SAMPLES is not None:
        logger.info(f"Limited to {MAX_SAMPLES} samples per split.")
        for k in split_dataset.keys():
            split_dataset[k] = split_dataset[k].select(range(min(MAX_SAMPLES, len(split_dataset[k]))))

    return split_dataset, image_col, label_col


def compute_metrics(eval_pred):
    logits, labels = eval_pred
    predictions = logits.argmax(axis=-1)
    accuracy = (predictions == labels).mean()
    return {"accuracy": float(accuracy)}


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    ensure_output_dir(OUTPUT_DIR)

    # 1. Загрузка процессора изображений
    logger.info(f"Loading model processor from '{MODEL_NAME}'")
    processor = AutoImageProcessor.from_pretrained(MODEL_NAME)

    # 2. Подготовка датасета
    dataset, image_col, label_col = load_and_prepare_dataset()

    # Определение количества классов и маппинга
    raw_labels = dataset["train"].features[label_col]
    if hasattr(raw_labels, "names"):
        id2label = {i: name for i, name in enumerate(raw_labels.names)}
        label2id = {name: i for i, name in enumerate(raw_labels.names)}
    else:
        id2label = {i: name for i, name in enumerate(WEATHER_LABELS)}
        label2id = {name: i for i, name in enumerate(WEATHER_LABELS)}
    num_labels = len(id2label)
    logger.info(f"Number of classes: {num_labels} -> {id2label}")

    # 3. Инициализация модели
    model = AutoModelForImageClassification.from_pretrained(
        MODEL_NAME,
        num_labels=num_labels,
        id2label=id2label,
        label2id=label2id,
        ignore_mismatched_sizes=True,
    )

    # 4. Collate function (преобразует PIL-картинки в тензоры батча)
    def collate_fn(batch):
        images = [item[image_col].convert("RGB") for item in batch]
        labels = [item[label_col] for item in batch]
        inputs = processor(images, return_tensors="pt")
        inputs["labels"] = torch.tensor(labels, dtype=torch.long)
        return inputs

    # 5. Параметры обучения
    training_args = TrainingArguments(
        output_dir=str(OUTPUT_DIR / "trainer_output"),
        num_train_epochs=NUM_EPOCHS,
        per_device_train_batch_size=BATCH_SIZE,
        per_device_eval_batch_size=BATCH_SIZE * 2,
        learning_rate=LEARNING_RATE,
        warmup_steps=100,
        weight_decay=WEIGHT_DECAY,
        fp16=FP16,
        eval_strategy="epoch",
        save_strategy="epoch",
        load_best_model_at_end=True,
        metric_for_best_model="accuracy",
        logging_steps=50,
        dataloader_num_workers=0,
        remove_unused_columns=False,  # Предотвращает удаление не-тензорных колонок до collate_fn
        report_to="none",
        push_to_hub=False,
    )

    # 6. Trainer
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=dataset["train"],
        eval_dataset=dataset["validation"],
        data_collator=collate_fn,
        compute_metrics=compute_metrics,
    )

    # 7. Запуск обучения
    logger.info("=== Starting training ===")
    trainer.train()

    # 8. Финальная оценка и запись метрик
    eval_results = trainer.evaluate()
    logger.info(f"Final evaluation: {eval_results}")

    metrics = {
        "model_name": MODEL_NAME,
        "dataset_name": DATASET_NAME,
        "num_classes": num_labels,
        "labels": id2label,
        "num_epochs": NUM_EPOCHS,
        "batch_size": BATCH_SIZE,
        "learning_rate": LEARNING_RATE,
        "final_eval": eval_results,
    }
    with open(METRICS_PATH, "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)
    logger.info(f"Training metrics saved to {METRICS_PATH}")

    # 9. Сохранение чекпоинта PyTorch
    torch.save(model.state_dict(), CHECKPOINT_PATH)
    logger.info(f"PyTorch checkpoint saved to {CHECKPOINT_PATH}")

    # 10. Экспорт в ONNX
    logger.info("Exporting model to ONNX format...")
    model.eval()
    device = next(model.parameters()).device

    first_image = dataset["validation"][0][image_col].convert("RGB")
    dummy_input = processor(first_image, return_tensors="pt")["pixel_values"].to(device)

    torch.onnx.export(
        model,
        dummy_input,
        str(ONNX_PATH),
        input_names=["input"],
        output_names=["output"],
        dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}},
        opset_version=13,
        do_constant_folding=True,
    )
    logger.info(f"ONNX model saved to {ONNX_PATH}")
    logger.info("=== Training complete ===")


if __name__ == "__main__":
    main()