# RaceLab Android 2.0

Нативный Android lap timer / telemetry / video recorder для работы полностью на телефоне.

Целевая установка: Android 16 / Realme GT6.  
Package: `ru.racelab.phone`  
Version: **2.0.0**

## Что реализовано

### Заезд и тайминг
- Нативный GNSS телефона.
- BLE NMEA GPS.
- USB-C OTG NMEA GPS через USB serial.
- Автоматический приоритет GNSS: USB → BLE → телефон.
- Ручной выбор AUTO / PHONE / BLE / USB.
- START/FINISH и до 3 секторов.
- Направленное пересечение линии и timestamp interpolation.
- Reverse crossing reject.
- ARM → ожидание START → автоматический круг.
- Current / Last / Best / Predictive lap / live Delta.
- Theoretical Best по секторам.
- Автостоп после длительной остановки после заезда.

### IMU телефона
- SensorManager TYPE_ALL для continuous/on-change датчиков.
- Accelerometer / linear acceleration / gyro / rotation vector / magnetometer / gravity и доступные датчики телефона.
- Калибровка положения телефона в креплении.
- Выбор края телефона, направленного вперёд.
- Longitudinal / Lateral / Vertical G.
- Yaw / Pitch / Roll.
- Частота каждого сенсора.
- sensors.csv.

### Диагностика
- GNSS Hz / satellites / accuracy.
- PDOP / HDOP / VDOP для NMEA GPS.
- IMU availability / frequency / calibration.
- Camera / microphone permissions.
- Bluetooth.
- Storage.
- Battery.
- Android thermal status.

### Трассы
- Сохранение START/FINISH и S1-S3.
- Локальная библиотека трасс.
- Автоматический выбор ближайшей трассы.
- JSON import/export через буфер обмена.
- GPX import/export через буфер обмена.

### Анализ
- REF / CMP выбор двух кругов.
- Speed vs distance.
- Delta vs distance.
- Theoretical Best.
- Цветная GPS-трасса по скорости.
- GPS accuracy.
- Автоматический поиск сильных зон BRAKE / ACCEL.
- Повторный анализ сохранённых сессий после перезапуска приложения (для сессий 2.0+ с lap_no).

### Видео
- CameraX foreground recording.
- 1080p / 4K profile.
- 30 / 60 FPS target.
- AUTO / H.264 / H.265.
- Bitrate setting.
- Video stabilization.
- Microphone.
- AUTO REC при старте сессии.
- Отдельные клипы по кругам.
- HUD в VIDEO_CAPTURE: speed / lap / delta / Long G / Lat G / RPM / throttle.
- Fallback на 1080p30 AUTO, если выбранный профиль не поддерживается камерой.
- Видео сохраняется в Movies/RaceLab.
- URI роликов связываются с папкой сессии.

### OBD-II / ELM327
- BLE scan/connect.
- Автоинициализация ELM327.
- Запрос supported PID bitmap.
- RPM.
- Vehicle speed.
- Throttle.
- Coolant temperature.
- Engine load.
- Intake temperature.
- MAP.
- Fuel pressure.
- Ignition timing.
- MAF.
- Voltage.
- Oil temperature.
- Short/long fuel trims.

### Custom PID / EV
Редактор Custom PID прямо в приложении:
- request hex;
- response prefix;
- 1-4 data bytes;
- signed / unsigned;
- scale;
- offset;
- unit;
- enable/disable;
- EV channel mapping.

EV dashboard:
- SOC;
- battery power;
- motor power;
- regen power;
- battery temperature;
- inverter temperature.

### USB-CAN
Поддерживается **SLCAN / Lawicel compatible USB serial CAN**:
- USB-C OTG.
- CDC/FTDI/CP210x/PL2303 классы через usb-serial-for-android.
- 10 / 20 / 50 / 100 / 125 / 250 / 500 / 800 / 1000 kbit/s.
- LISTEN-ONLY по умолчанию.
- Приложение не отправляет CAN data frames.
- Standard 11-bit и Extended 29-bit frames.
- can.csv.
- can_signals.csv.

Редактор CAN signals:
- CAN ID;
- standard / extended;
- start bit;
- bit length 1-32;
- little / big endian;
- signed;
- scale / offset;
- unit;
- mapping в RPM / Speed / Throttle / Gear / Steering / Brake pressure / wheel speeds / temperatures / EV.

> CAN FD и native GS_USB в 2.0 не реализованы. Для USB-CAN 2.0 используется SLCAN ASCII.

### Архив сессий
Вкладка **АРХИВ**:
- дата/время;
- трасса;
- количество кругов;
- BEST;
- Vmax;
- GPS / IMU / OBD / CAN / VIDEO indicators;
- список кругов;
- открытие связанных видео;
- анализ старых кругов;
- удаление;
- размер данных.

Экспорт в `Downloads/RaceLab`:
- ZIP — вся папка сессии;
- CSV — основная GPS/OBD/EV телеметрия;
- JSON — summary/laps/video refs;
- VBO;
- NMEA.

После экспорта файл можно сразу отправить через Android Share Sheet.

## Файлы одной сессии

`Android/data/ru.racelab.phone/files/RaceLab/sessions/session_YYYYMMDD_HHMMSS/`

- `gps.csv`
- `sensors.csv`
- `obd_custom.csv`
- `can.csv`
- `can_signals.csv`
- `videos.txt`
- `meta.json`

## Сборка

GitHub Actions: `.github/workflows/android.yml`

CI выполняет:
1. JDK 17.
2. Android SDK API 36.
3. Unit tests.
4. `:app:assembleDebug`.
5. Upload artifact `RaceLab-Android-debug`.

## Ограничения 2.0

- Реальная доступность 4K60/H.265 зависит от Camera2/CameraX профилей конкретного телефона.
- Custom OBD PID и CAN signals требуют формул/ID конкретного автомобиля.
- USB-CAN сейчас SLCAN, CAN FD не поддерживается.
- Старые сессии до 2.0 отображаются и экспортируются, но повторный REF/CMP анализ требует `lap_no`, который записывается начиная с 2.0.


### PIT STOP
- Отдельный pit-stop timer на GT3 dashboard.
- Текущий PIT TIME, LAST, BEST и счётчик пит-стопов.
- Резервная экранная PIT-кнопка.
- Физическая кнопка руля через BLE/USB HID:
  - F1;
  - BUTTON_1 / BUTTON_A;
  - PLAY/PAUSE;
  - HEADSETHOOK;
  - CAMERA;
  - внешние Volume +/- кнопки BLE HID.
- Кнопка штатного руля через USB-CAN:
  - создать CAN signal;
  - Channel = `PIT_BUTTON`;
  - срабатывание по фронту значения > 0.5.
- Debounce 250 ms.
- CAN остаётся listen-only: RaceLab не передаёт управляющие CAN frames.
- Каждое START / STOP / RESET сохраняется в `pit_events.csv`.
- При остановке сессии активный PIT автоматически завершается и сохраняется.


### HOCO GM204
Специальный профиль Bluetooth HID-пульта HOCO GM204:
- Camera / F1 / Button1 / внешние Volume +/- → PIT START/STOP.
- OK / Play-Pause / Enter / D-pad center → RESET PIT только когда PIT остановлен.
- D-pad Left / Right → предыдущая / следующая вкладка.
- D-pad Up → Дашборд.
- D-pad Down → Данные.
- Профиль включается/выключается в Настройки → PIT BUTTON.
- В настройках отображаются имя внешнего HID-устройства и последний Android keycode, чтобы проверить конкретную ревизию пульта.


### Landscape cockpit
- RaceLab 2.4.0 фиксируется в `sensorLandscape`.
- GT3 dashboard имеет отдельную альбомную компоновку без вертикального скролла.
- Safe drawing insets защищают интерфейс от вырезов, системных панелей и жестовой навигации.
- Верх: GPS/REC, текущий круг, лучший круг, delta, номер круга.
- RPM — компактная горизонтальная полоса.
- Центр: скорость, газ/тормоз, масло/ОЖ, PIT, G-meter, карта.
- Нижняя навигация уменьшена до 48 dp и сохраняет пять основных вкладок.
- Текстовые поля ограничены одной строкой, чтобы карточки не выходили за границы.
