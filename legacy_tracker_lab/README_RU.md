# Legacy Tracker Lab

Контрольный стенд старой простой архитектуры tracking.

## Зачем

Сравнить на одном и том же видеопотоке:

1. YOLO + 8-state Kalman
2. YOLO + CSRT
3. YOLO + KCF
4. YOLO + MOSSE

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

Основная камера:
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


## Автоматический A/B всех 4 режимов

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
