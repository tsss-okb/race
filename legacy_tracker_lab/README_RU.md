# Legacy Tracker Lab

Контрольный стенд старой простой архитектуры tracking.

## Зачем

Сравнить на одном и том же видеопотоке:

1. YOLO + KCF Hybrid (рекомендуемый)
2. YOLO + 8-state Kalman
3. YOLO + CSRT
4. YOLO + KCF
5. YOLO + MOSSE

Сначала выбираем лучший visual-tracking core. Только потом переносим победителя в Android.

## Архитектура

### KALMAN
```
USB camera / MP4
      ↓
YOLO
      ↓
выбранный class id
      ↓
nearest same-class detection
      ↓
bbox history (3)
      ↓
Kalman [cx,cy,w,h,vx,vy,vw,vh]
      ↓
prediction during short detector loss
```

### CSRT / KCF / MOSSE
```
USB camera / MP4
      ↓
YOLO acquisition
      ↓
OpenCV tracker every frame
      ↓
periodic YOLO verification
      ↓
reinitialize tracker if needed
```

## Установка

```bash
cd legacy_tracker_lab
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

На Raspberry Pi 5:
```bash
sudo apt update
sudo apt install -y python3-venv libgl1 libglib2.0-0
```

## Быстрый тест USB-камеры

Основная камера — текущий рекомендуемый режим:
```bash
python run_camera.py --source 0 --model yolo26n.pt --mode kcf-hybrid
```

KCF Hybrid работает так:
```
YOLO acquire
   ↓
KCF every frame
   ↓
YOLO verify every 3 frames
   ↓
если KCF ушёл от motion-prior → reject
   ↓
COAST / predictor
   ↓
расширяющееся окно YOLO reacquire
   ↓
KCF re-init
```

Чистый Kalman:
```bash
python run_camera.py --source 0 --model yolo26n.pt --mode kalman
```

CSRT:
```bash
python run_camera.py --source 0 --model yolo26n.pt --mode csrt
```

KCF:
```bash
python run_camera.py --source 0 --model yolo26n.pt --mode kcf
```

MOSSE:
```bash
python run_camera.py --source 0 --model yolo26n.pt --mode mosse
```

Для самолёта в обычной COCO-модели:
```bash
python run_camera.py --source 0 --model yolo26n.pt --class-id 4 --mode kalman
```

Если используется собственная модель, укажите её class id.

## Управление

- ЛКМ по YOLO bbox — выбрать цель.
- R — сбросить удержание.
- 0 — KCF Hybrid.
- 1 — Kalman.
- 2 — CSRT.
- 3 — KCF.
- 4 — MOSSE.
- Q / ESC — выход.

## Метрики

CSV пишется автоматически:
- detector latency;
- detector FPS;
- tracker FPS;
- selected class;
- lost frames;
- reacquire count;
- bbox center jitter;
- selected mode.

Видео с overlay можно включить параметром:
```bash
--record legacy_test.mp4
```

## Важно

Этот стенд пассивный: он только детектирует и удерживает объект в кадре.
MAVLink/ArduPilot команды здесь отсутствуют.


## Автоматический A/B всех 5 режимов

Один ролик автоматически прогоняется через Kalman, CSRT, KCF и MOSSE:

```bash
python benchmark_all.py \
  --video test.mp4 \
  --model yolo26n.pt \
  --class-id 4 \
  --out-dir benchmark_out
```

Скрипт создаст:

- `benchmark_out/kalman_overlay.mp4`
- `benchmark_out/csrt_overlay.mp4`
- `benchmark_out/kcf_overlay.mp4`
- `benchmark_out/mosse_overlay.mp4`
- отдельные CSV по каждому режиму
- `benchmark_out/tracker_summary.csv`

Итоговая таблица сравнивает:

- hold ratio;
- average YOLO inference latency;
- detector FPS;
- tracker FPS;
- mean jitter;
- p95 jitter;
- peak lost frames;
- reacquire count.

Победитель печатается строкой `WINNER: ...`.


## Результат реального теста YOLO26n

На загруженном тестовом ролике (264×148, цель очень маленькая) официальный YOLO26n ONNX был прогнан через ONNX Runtime.

На участке кадров 20–90:
- YOLO26n CPU: около 78 мс среднего inference, p95 около 93 мс;
- чистый KCF был самым точным, пока цель оставалась видимой, но быстро терял её;
- CSRT продолжал выдавать bbox после потери и уходил в drift;
- чистый Kalman также продолжал prediction слишком долго;
- KCF Hybrid с verify каждые 3 кадра и full-frame reacquire после длительной потери дал:
  - median center error ≈ 2.25 px;
  - mean IoU ≈ 0.45;
  - около 81.7% reference-кадров в пределах 10 px;
  - 2 успешных reacquire.

Поэтому KCF Hybrid теперь является режимом по умолчанию.
