# Архив прежней инженерной истории

До введения общей схемы координации несколько чатов дописывали большой `DEVELOPMENT_HISTORY.txt` в экспериментальной ветке. Там записаны многочисленные детали расследования LG G6, исходников, PR, ошибок сборки, Camera2/ISP и тестирования.

Снимок этого файла на 2026-11-19 сохранён в [DEVELOPMENT_HISTORY_2026-11-19.txt](DEVELOPMENT_HISTORY_2026-11-19.txt), чтобы **любой новый чат мог читать его из `main`**, независимо от состава рабочей ветки. Копия историческая; не дописывайте туда новые события. Для свежих изменений используются `main/docs/sessions/YYYY-MM-DD/<unique>.md` и `main/docs/workstreams/<domain>/<unique>.md`.

Дополнительные первоисточники, которые могут иметь более новые детали или исходные данные:
- [DEVELOPMENT_HISTORY.txt на экспериментальной ветке](https://github.com/Delliaf/android-ip-camera/blob/fix/lg-g6-metrics-visible/DEVELOPMENT_HISTORY.txt).
- [Подробный LG G6 Main0 handoff](https://github.com/Delliaf/android-ip-camera/blob/fix/lg-g6-metrics-visible/LG_G6_H872_MAIN0.md).
- [Автотест 4,5×](https://github.com/Delliaf/android-ip-camera/blob/feature/lg-main0-auto-image-benchmark/LG_G6_MAIN0_IMAGE_BENCHMARK.md).
- [Базовая Android8 документация](https://github.com/Delliaf/android-ip-camera/blob/feature/manual-camera-4k-stream/FORK_ANDROID8.md).
- [Все PR](https://github.com/Delliaf/android-ip-camera/pulls) — всегда проверяй актуальные `base/head` и diff.
- **Не путай** старый исторический факт `CI success` с состоянием более нового коммита.

Если исходные фото и оригинальные приватные логи/библиотеки не находятся в репозитории — это намеренно: не копировать пользовательские файлы в публичный GitHub. При необходимости запрашивать безопасно обезличенный результат либо ссылку на предыдущий user upload с доступом в текущем чате.
