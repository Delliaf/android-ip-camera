# Автоматический сравнительный тест изображения: 4,5×

**Текущий код:** [PR #4](https://github.com/Delliaf/android-ip-camera/pull/4), его функции присутствуют также в [PR #5](https://github.com/Delliaf/android-ip-camera/pull/5). Предыдущая инструкция: [LG_G6_MAIN0_IMAGE_BENCHMARK.md](https://github.com/Delliaf/android-ip-camera/blob/feature/lg-main0-auto-image-benchmark/LG_G6_MAIN0_IMAGE_BENCHMARK.md).

## Пользовательская идея

Сам телефон должен прогонять режимы и параметры камеры при **фиксированном 4,5×**, выбирать максимально возможное **реально выдаваемое** разрешение, измерять выходную картинку, собирать ZIP с примерами JPEG и отчётами; без ручного переключения каждой настройки.

## Действующая реализация (экспериментально, device-test ещё не доказан)

- `LgBenchmarkPlan.kt`: quick до 24 комбинаций; deep до 90. Шаблоны `PREVIEW=1`, `RECORD=3`, `ZSL=5`; ISP sensor crop vs CPU ROI; стандартные перечислимые NR/EDGE/AWB/aberration, репрезентативные значения ISO, shutter, focus (не все бесконечно много вещественных значений).
- `LgMain0ImageBenchmark.kt`: только LG H872 Camera2 ID0. Пробует YUV_420_888 размеры от максимального, и **убеждается, что реальные кадры выходят**, иначе переходит на меньший. На каждый кейс применяет request, собирает frame, фактический TotalCaptureResult (по SENSOR_TIMESTAMP при наличии), ошибки, температурные ограничения; сохраняет ZIP.
- `LgBenchmarkImaging.kt`: нормализованный 4,5× crop YUV, JPEG, яркость/контраст, чёрно-белый клиппинг, luma P01/P99, edge energy, Laplacian variance, temporal difference. Высокие значения резкости могут означать oversharpening/шум, а не больше реальных деталей.
- `LgMain0BenchmarkActivity.kt`: кнопка рядом с Settings, локальный Quick/Deep экран, 4:3 / 1:1, Stop, Save ZIP через Android Storage Access Framework. Stream RTSP/ONVIF на время теста приостанавливается, затем должен возобновиться при возврате.
- ZIP: `device.json` (capabilities), `report.json` (параметры и метрики), `results.csv` (короткие цифры), `samples/*.jpg` (картинки), `README.txt`.
- Угол обзора уменьшен 4,5×, но это **не оптическое приближение**. Источник 4160×3120 при таком zoom даёт ~924×693 native ROI (квадрат ~693×693). Не выдавать итоговый JPEG за «13 Мп честного зума».

## Что тест пока не доказывает

- Равенство штатному LG PHOTO preview: прямой Camera2 TEMPLATE_ZSL не включает все закрытые LG ISP/CPP параметры.
- Что для high-res `YUV_420_888` камера даст 4160×3120 и стабильные FPS; JPEG/PRIVATE и YUV могут иметь разные лимиты.
- Гарантированную синхронизацию фокуса/экспозиции между разными режимами в сцене без штатива.
- Автоматический выбор лучшего для человеческого глаза качества; метрики — вспомогательные признаки, а не ground truth.
- Долговременную thermal стабильность и корректность восстановления потока после завершения на реальном H872.

## Проверки

Последняя подтверждённая CI для первого бенчмарка: версия `.7`, [run 44561531501](https://github.com/Delliaf/android-ip-camera/actions/runs/44561531501), код SHA `bbe1141f069dd04666deb5c9ce9e5401bc6e802b`. Поздняя версия `.8` наследует тест и [CI run 44591663439](https://github.com/Delliaf/android-ip-camera/actions/runs/44591663439) прошла. Для новых изменений ищи новую CI с точным SHA. Не загружай JPEG/ZIP личного видео в публичный repo — только обезличенную диагностику.
