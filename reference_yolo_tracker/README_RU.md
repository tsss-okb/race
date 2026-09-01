# Reference YOLO + Kalman Tracker

Это отдельный пассивный контрольный стенд для сравнения качества удержания цели.

Он повторяет простую схему из старого рабочего проекта:

```
YOLO
  ↓
тот же класс
  ↓
ближайшая рамка к прошлому прогнозу
  ↓
усреднение 3 bbox
  ↓
Kalman [cx, cy, w, h, vx, vy, vw, vh]
  ↓
prediction при краткой потере детекции
  ↓
reset после timeout
```

Здесь специально **нет MAVLink и команд управления**. Стенд нужен только для измерения качества visual tracking.

## Быстрый синтетический тест

```bash
python synthetic_test.py
```

Он проверяет:
- краткие пропуски детектора;
- повторный захват;
- соседний объект того же класса;
- отсутствие перескока на дальний distractor.

## Тест на реальном видео

```bash
pip install -r requirements.txt

python benchmark.py \
  --video test.mp4 \
  --model best.pt \
  --class-id 0 \
  --no-display
```

Результат:
- `reference_overlay.mp4` — видео с YOLO и Kalman-рамкой;
- `reference_metrics.csv` — покадровые метрики;
- в консоли: processing FPS, average inference latency, corrections, reacquires.

Если используется обычная COCO-модель и нужен самолёт:
```bash
python benchmark.py --video test.mp4 --model yolo11n.pt --class-id 4
```

Для собственной модели укажите её class ID.

## Что сравниваем с Android TargetLock

1. Сколько кадров цель удерживается без сброса.
2. Сколько успешных reacquire.
3. Есть ли перескок на другой объект того же класса.
4. Дрожание центра bbox.
5. Средняя задержка YOLO.
6. Поведение при кратком пропадании детекции.

Если Reference Tracker объективно лучше Android-контура, переносим именно эту простую схему в Java, не добавляя лишние фильтры.
