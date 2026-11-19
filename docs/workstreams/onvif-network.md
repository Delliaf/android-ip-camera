# RTSP / ONVIF / go2rtc / HTTPS

**Базовый оригинал:** Android IP Camera поддерживает RTSP(H.264/AAC) и ONVIF, веб-MJPEG/H.264 и TLS. Экспериментальные правки: [PR #3](https://github.com/Delliaf/android-ip-camera/pull/3), позже унаследованы в #4 и #5.

## Наблюдение пользователя

go2rtc отправлял `POST http://192.168.1.38:8000/onvif/device_service` и получал `connection reset by peer`; установка пароля не помогала. **IP — пример из пользовательской диагностики, не кодировать его в ПО/логи/публичные примеры как секрет.**

## Найденная реализация и рабочая гипотеза

Изначально `OnvifServer` использовал общую `TlsHelper.serverConfig`, а HTTPS по умолчанию был включён. Клиент `onvif://` мог отправлять **незашифрованный HTTP на TLS socket**, что приводит к обрыву ещё до проверки credentials.

В экспериментальном `StreamingService.kt` ONVIF TLS теперь выделен в независимую настройку `onvif_tls_enabled` (по умолчанию true); в Android `preferences.xml` пользователю можно явно отключить **ONVIF over HTTPS**, не затрагивая Web HTTPS или RTSPS. `OnvifServer.kt` логирует TLS mismatch, `OnvifServerTest.kt` покрывает POST/GetCapabilities/MediaProfiles в HTTP варианте.

**Не утверждать, что реальная ошибка go2rtc подтверждённо решена, пока не проверено на устройстве**: также возможны неверный ONVIF endpoint, auth, SOAP/WS-Discovery, NAT, ошибочная версия приложения, ошибки прошивки.

## Файлы

`helpers/OnvifServer.kt`, `helpers/OnvifSoap.kt`, `helpers/RtspServer.kt`, `helpers/StreamingServerHelper.kt`, `helpers/TlsHelper.kt`, `StreamingService.kt`, `app/src/main/res/xml/preferences.xml`, `helpers/OnvifServerTest.kt`.

## Контрольные проверки

1. Проверить какой протокол слушает ONVIF порт: `http://` или `https://`. Подключаться по правильному протоколу, не перебирая пароли при TLS handshake mismatch.
2. Убедиться, что go2rtc получает HTTP 200/401/403/405, а не connection reset, и SOAP `GetCapabilities` + `GetProfiles` возвращают содержательные ответы.
3. Проверить RTSP у реального клиента (URL, SPS/PPS, frame rate, ONVIF profiles/timestamps, H.264/AAC) и восстановление после Camera2 benchmark.
4. Никогда не открывать незащищённый ONVIF/RTSP наружу без VPN, даже если в LAN пользователь предпочитает без пароля.

Для новых задач создавай **свой** session файл в main, свою branch и draft PR, фиксируй реальную ошибку и точный SHA.
