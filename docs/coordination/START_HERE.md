# START HERE — Android IP Camera / LG G6

**Проект:** https://github.com/Delliaf/android-ip-camera — форк [DigitallyRefined/android-ip-camera](https://github.com/DigitallyRefined/android-ip-camera) с экспериментальными доработками под LG G6 H872.

**Дата первичного снимка:** 2026-11-20. Перед любой работой сверяй с живыми GitHub refs/PR/Actions — эта страница не заменяет чтение репозитория.

## Самое важное

- `main` — upstream-подобная стабильная база **без** большинства экспериментальных функций LG G6.
- Разработка распределена между **черновыми stacked PR** и несколькими ветками. Самый полный текущий экспериментальный код — в `fix/lg-g6-metrics-visible` (на дату снимка). Пять открытых draft PR не означают пять независимых релизов; у них зависимости.
- Цель: качественный видеопоток основной Camera2 ID `0` LG-H872, использование фактического высокого разрешения, 4×/4,5× ROI, ISP/GPU A/B, выдержка/ISO/фокус, RTSP/ONVIF, датчики CPU/температуры, локальные тесты картинки.
- Android 8 совместимость; сборки APK **только arm64-v8a**. `minSdk=24` в коде не доказывает реальную совместимость на Android 8.
- Root доступен на тестовом устройстве, но нельзя принимать наличие root за разрешение менять системные библиотеки или SELinux.
- **Подтверждено CI:** `0.18.0-delliaf.8`, коммит `5853755367ae8dc99c338851b2de22214ec8b07a`, [Actions run 44591663439](https://github.com/Delliaf/android-ip-camera/actions/runs/44591663439), artifact `android-ip-camera-android8-debug`. **Не подтверждено:** полная работа на физическом LG-H872. Последующие изменения журнала не меняли APK.

## Как читать проект за несколько минут

1. [AGENTS.md](../../AGENTS.md) — правила для любого чата.
2. [BRANCHES_AND_PRS.md](BRANCHES_AND_PRS.md) — дерево зависимостей PR + как найти фактический HEAD.
3. [PARALLEL_WORKFLOW.md](PARALLEL_WORKFLOW.md) — как делить задачи, вести общие записи и не терять изменения.
4. [PROJECT_ARCHITECTURE.md](PROJECT_ARCHITECTURE.md) — карта реальных файлов, путей захвата, сетевых протоколов.
5. [RELEASE_AND_TEST_POLICY.md](RELEASE_AND_TEST_POLICY.md) — чем отличаются compile, CI, APK и тест устройства.
6. [docs/workstreams/README.md](../workstreams/README.md) — подробности направлений.
7. [docs/sessions/README.md](../sessions/README.md) — живая хронология **каждого отдельного чата**.
8. [docs/decisions/README.md](../decisions/README.md) — зафиксированные решения и мотивы.
9. Исторический `DEVELOPMENT_HISTORY.txt` на нужной feature-ветке и [архивная копия](../history/README.md) — исходные эксперименты, CI, гипотезы и ошибки. Не игнорируй оригинальные подробности.

## Что делать, когда приходит новая задача

**Не спрашивать пользователя пересказывать прошлые чаты.** Сначала прочитать перечисленное, выбрать точную ветку-основу и посмотреть пересекающиеся PR. Затем создать собственную ветку и уникальную запись задачи в `main/docs/sessions`; работать, проверять и регулярно обновлять GitHub. При необходимости исследовать до уровня исходников и реальных логов. Не обобщать результаты, которые нельзя проверить.

## Точки подключения

- Репозиторий: https://github.com/Delliaf/android-ip-camera
- Все PR: https://github.com/Delliaf/android-ip-camera/pulls
- GitHub Actions: https://github.com/Delliaf/android-ip-camera/actions
- Историческая ветка с последними LG-функциями (на момент снимка): https://github.com/Delliaf/android-ip-camera/tree/fix/lg-g6-metrics-visible
- Документы универсального управления живут **всегда в main**; читать `main` явно, даже если работаешь на feature-ветке.
