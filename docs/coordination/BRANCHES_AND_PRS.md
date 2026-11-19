# Ветки и PR: снимок зависимостей (2026-11-20)

**Важно:** это ориентир, **НЕ live-истина**. Каждый агент обязан получить актуальные GitHub branches, pull requests, base/head SHA, CI и только затем выбрать основу новой задачи. Не мержить черновики без запроса пользователя.

```text
main (upstream-подобная база)
└── PR #1 feature/manual-camera-4k-stream → main
    ├── PR #2 feature/lg-h872-main0 → feature/manual-camera-4k-stream
    └── PR #3 fix/onvif-go2rtc-telemetry → feature/manual-camera-4k-stream
        └── PR #4 feature/lg-main0-auto-image-benchmark → fix/onvif-go2rtc-telemetry
            └── PR #5 fix/lg-g6-metrics-visible → feature/lg-main0-auto-image-benchmark
```

| PR | Ветка / база | Что там |
|---|---|---|
| [#1](https://github.com/Delliaf/android-ip-camera/pull/1) | `feature/manual-camera-4k-stream` → `main` | CameraX manual, GPU ROI, LG G6 4:3 и квадрат, direct Camera2 ZSL/RECORD, arm64 CI |
| [#2](https://github.com/Delliaf/android-ip-camera/pull/2) | `feature/lg-h872-main0` → `feature/manual-camera-4k-stream` | Отдельная параллельная линия OEM LG vendor probe, фокус/ISO |
| [#3](https://github.com/Delliaf/android-ip-camera/pull/3) | `fix/onvif-go2rtc-telemetry` → `feature/manual-camera-4k-stream` | ONVIF HTTP/TLS для go2rtc; базовые CPU/температура |
| [#4](https://github.com/Delliaf/android-ip-camera/pull/4) | `feature/lg-main0-auto-image-benchmark` → `fix/onvif-go2rtc-telemetry` | Локальный Camera2 автотест 4,5×, JPEG/JSON/CSV ZIP, журнал |
| [#5](https://github.com/Delliaf/android-ip-camera/pull/5) | `fix/lg-g6-metrics-visible` → `feature/lg-main0-auto-image-benchmark` | CPU/температура всегда видимы, диагностические причины, Qualcomm tsens и опциональный root |

## Последний известный успешный APK

- Experimental `0.18.0-delliaf.8`, Android 8+, **arm64-v8a**, [Actions run 44591663439](https://github.com/Delliaf/android-ip-camera/actions/runs/44591663439)
- **Тестируемый функциональный SHA:** `5853755367ae8dc99c338851b2de22214ec8b07a`
- Artifact: `android-ip-camera-android8-debug` в разделе Artifacts запуска.
- Следующие коммиты PR #5 были документационными; это утверждение актуально **только на момент снимка**. Перепроверь перед новым релизом.
- CI компиляция/тесты зелёные, **реальная работа устройства и все режимы камеры пока не подтверждены**.

## Если новая задача затрагивает другой модуль

- Независимая общая функция: прочитай код и зависимости; если не требует экспериментального LG-стека, можно брать `main`.
- Настройки или исправления последних экспериментальных функций: обычно основа `fix/lg-g6-metrics-visible`, но проверь наличие более свежих веток/PR.
- Экспериментальная функция из PR #2, отсутствующая в ветке PR #5: нельзя считать её объединённой; сначала сравни diff/конфликты, выбери базу вместе с явной зависимостью.
- Интегратор обязан сверить параллельные линии #2 и #3/#4/#5, а не предполагать, что они совместимы автоматически.

**Менять этот общий снимок — задача интегратора.** Каждый независимый чат добавляет свою текущую ветку/PR в **свой** `main/docs/sessions` файл; другие чаты находят их через GitHub tree и открытые PR.
