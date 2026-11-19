# Android 8 / arm64-v8a / GitHub CI

## Как устроено

В `main` остаётся базовая конфигурация DigitallyRefined. Экспериментальные ветки содержат `.github/workflows/android8-fork-ci.yml` и собственный `app/build.gradle`. Не считать release `main` равным экспериментальному `0.18.0-delliaf.*`.

- Android Gradle Plugin / Kotlin / Java 17, compile SDK34; проверяй точные версии на нужном SHA.
- Rust OpenTunnel JNI для `aarch64-linux-android` и APK `arm64-v8a`. CI выполняет сборку + JVM-тесты + проверку `unzip -Z1` для `lib/arm64-v8a/` и отсутствия остальных ABI.
- Не совмещать Android Gradle `ndk.abiFilters` и `splits.abi` для одного APK: ранее это приводило к конфигурационному сбою.
- Файл `scripts/ci-gradle-retry.sh` в экспериментальных ветках повторяет **только** временные ошибки Google/Maven, не скрывая ошибки Kotlin/тестов.
- `scripts/test-metrics-dashboard.cjs` — проверка JavaScript оверлея (в последних ветках), `node --check` — синтаксис embedded JS.
- Частые документационные коммиты **в main** исключены из тяжёлого `reproducible-build.yml` по `paths-ignore` для `docs/**`/`AGENTS.md`/`README.md`, чтобы параллельная запись истории не тратила минуты GitHub Actions.

## Последняя известная экспериментальная сборка

- `0.18.0-delliaf.8`, [run 44591663439](https://github.com/Delliaf/android-ip-camera/actions/runs/44591663439), functional SHA `5853755367ae8dc99c338851b2de22214ec8b07a`, artifact `android-ip-camera-android8-debug` (GitHub ZIP, содержит debug APK).
- **CI passed**, arm64 libs verified. **H872 physical test still pending**.
- До `.8` автоматический image benchmark был в `.7`, [run 44561531501](https://github.com/Delliaf/android-ip-camera/actions/runs/44561531501).

## Для нового чата

1. Создать **уникальную ветку** от правильного code base; `android8-fork-ci.yml` может быть ограничен перечисленными branch names: проверь текущий `on.push.branches` и добавь запуск для своей ветки (желательно безопасным workflow_dispatch/PR, без изменения чужих проверок) либо обеспечь локальную сборку.
2. При каждой сборке сверить SHA в GitHub Actions с **последним коммитом кода**; если потом коммитились только документы, укажи это отдельно.
3. Сохранять точные FAILED/CANCELLED/SUCCESS runs и причины в собственном session файле main; зелёный unit-test не доказывает аппаратную работу на телефоне.
4. Debug APK может отличаться ключом подписи от установленной версии — предупреди, сохранение настроек перед переустановкой.
5. Не создавать публичный релиз и не мержить draft PR без согласования пользователя.
