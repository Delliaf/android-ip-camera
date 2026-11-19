# CPU / SoC / батарея, веб-оверлей и телеметрия

**Текущий эксперимент:** [PR #5](https://github.com/Delliaf/android-ip-camera/pull/5); включён в `0.18.0-delliaf.8`. Корректность на реальном H872 ещё требуется подтвердить.

## Сообщение пользователя

Заряд и Wi-Fi видны, но **нагрузка CPU и температура не отображались**. В старом dashboard скрывались `#cpuStatus` при `cpuPercent=-1` (первый замер) и `#temperatureStatus` при `temperatureCelsius=null`. Ошибки `fetch('/metrics.json')` молча игнорировались. Браузер мог показывать закешированный `index.html` без новых элементов.

## Исправления в экспериментальном коде

- `helpers/DevicePerformanceMonitor.kt`: чтение агрегированного CPU через дельту `/proc/stat`, fallback `Process.getElapsedCpuTime` для **App CPU** (не путать с общей загрузкой); температура `/sys/class/thermal` с корректным маркированием CPU/SoC, включая Qualcomm `tsens_tz_sensorN`. Если датчик недоступен — fallback к `BatteryManager.EXTRA_TEMPERATURE` с подписью **Battery**, никогда не называть температурой CPU.
- Для rooted LG есть **опциональная** настройка `metrics_root_thermal_enabled` (OFF по умолчанию): read-only фиксированные `su -c` запросы, ограниченное ожидание и прекращение попыток после ошибки. Это не модификация HAL.
- `helpers/StreamingServerHelper.kt`: авторизованный `/metrics.json` (every ~5s) с `cpuPercent`, `cpuScope`, `cpuNote`, `temperatureCelsius`, `temperatureSource`, `temperatureNote`; `metricsVersion=2`. У `index.html` cache no-store, `/?v=8` теперь обрабатывается.
- `app/src/main/assets/index.html`: статус **не скрывается** при ошибке: `CPU …`, `CPU —`, `Temp —`; показывает `CPU NN%`, `App CPU NN%`, `SoC NN°C` либо `Battery NN°C`, ошибка запроса в консоли и подсказке.
- `scripts/test-metrics-dashboard.cjs` выполняет реальную функцию render в эмулированном DOM на трёх состояниях; `DevicePerformanceMonitorTest.kt` проверяет CPU math / thermal parsing.
- `app/src/main/res/xml/preferences.xml`: пользователь может явно разрешить чтение защищённых сенсоров через root.

## Проверки и ограничения

Подтверждено: **успешная сборка ARM64** `0.18.0-delliaf.8` [run 44591663439](https://github.com/Delliaf/android-ip-camera/actions/runs/44591663439), код SHA `5853755367ae8dc99c338851b2de22214ec8b07a`; JS/Kotlin тесты зелёные, artifact загружен. Не подтверждено: каким образом прошивка H872 реально отдаёт thermal, влияет ли root и сколько CPU расходует мониторинг при долгом стриме.

Для новой задачи: получить live ответ `/metrics.json` и проверку в веб-интерфейсе после 10 с. На устройстве не угадывать, что `Battery` равно `CPU`. Проверять отсутствие регрессий при закрытом экране, смене Wi-Fi и рестарте сервиса.
